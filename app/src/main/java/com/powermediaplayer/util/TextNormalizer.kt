package com.powermediaplayer.util

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Locale-aware string normalization shared by sort comparators and OCR
 * post-processing. Handles:
 *
 *   - Curly / typographic apostrophes & quotes mapped to ASCII equivalents
 *     (so "O'Brien" and "O’Brien" sort and search the same way)
 *   - Zero-width joiners, BOM, and other invisible Unicode formatting
 *     characters that leak in from copy/paste and from OCR output
 *   - NFC normalization so combining-diacritic forms compare equal to
 *     pre-composed forms (résumé as one codepoint vs e + combining acute)
 *
 * The companion [Collator] is configured with PRIMARY strength (ignores
 * case + accents) and is the right comparator for human-friendly sort
 * order across all scripts the OS knows about.
 */
object TextNormalizer {

    private val INVISIBLE_FORMATTING = Regex("[\\u200B-\\u200D\\uFEFF]")

    // Hoisted off [fixMojibake] — that runs per row per emission on the
    // Compose-collected Last Played flow, so allocating this Set per call was
    // pure churn on the hot path. Windows-1252 chars that signal UTF-8-misread
    // mojibake; any string with none of these can't be mojibake'd.
    private val MOJIBAKE_MARKERS = setOf('â', 'Ã', '€', '™', 'š', 'ž', 'œ')

    // Audit 4.1 — Collator.getInstance is an expensive ICU construction
    // and was being built PER COMPARISON inside library sorts. One cached
    // instance per locale; key generation synchronises on it (Collator is
    // not thread-safe), key comparison is a lock-free byte compare.
    @Volatile private var cachedCollator: Collator? = null
    @Volatile private var cachedCollatorLocale: Locale? = null

    /** Collator for the user's current locale. PRIMARY strength → case + accent insensitive. */
    fun collator(locale: Locale = Locale.getDefault()): Collator {
        val c = cachedCollator
        if (c != null && locale == cachedCollatorLocale) return c
        return (Collator.getInstance(locale).apply { strength = Collator.PRIMARY })
            .also { cachedCollator = it; cachedCollatorLocale = locale }
    }

    /** Thread-safe collation-key generation on the shared collator. */
    fun collationKey(s: String, locale: Locale = Locale.getDefault()): java.text.CollationKey {
        val c = collator(locale)
        synchronized(c) { return c.getCollationKey(s) }
    }

    /**
     * Repair "mojibake" — UTF-8 bytes mistakenly decoded as Windows-1252.
     * The classic example: the right curly apostrophe ' (U+2019) is UTF-8
     * bytes E2 80 99. Decoded as CP1252 those bytes become "â€™" — three
     * codepoints (U+00E2, U+20AC, U+2122). ISO-8859-1 has no glyph at
     * 0x80 (€) or 0x99 (™), so encoding back via Latin-1 would silently
     * lose those characters. Windows-1252 is the correct round-trip.
     *
     * Idempotent: returns the original string when re-decoding would
     * lose information or fail to improve the result.
     */
    fun fixMojibake(s: String): String {
        if (s.isEmpty()) return s
        // Quick reject — strings with no characters in the Windows-1252
        // mojibake-suspect range can't possibly be mojibake'd.
        if (s.none { it in MOJIBAKE_MARKERS }) return s
        return try {
            val cp1252 = java.nio.charset.Charset.forName("windows-1252")
            val bytes = s.toByteArray(cp1252)
            val candidate = String(bytes, Charsets.UTF_8)
            val hadReplacement = candidate.contains('�')
            val nonAsciiBefore = s.count { it.code > 127 }
            val nonAsciiAfter = candidate.count { it.code > 127 }
            if (!hadReplacement && nonAsciiAfter < nonAsciiBefore) candidate else s
        } catch (_: Exception) {
            s
        }
    }

    /**
     * Returns a normalized form of [s] suitable for display, search, and
     * comparison. Idempotent — re-normalizing produces the same output.
     */
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        val unmojibake = fixMojibake(s)
        val nfc = Normalizer.normalize(unmojibake, Normalizer.Form.NFC)
        return nfc
            .replace('‘', '\'')   // left single curly
            .replace('’', '\'')   // right single curly / typographic apostrophe
            .replace('ʼ', '\'')   // modifier letter apostrophe
            .replace('“', '"')    // left double curly
            .replace('”', '"')    // right double curly
            .replace('–', '-')    // en dash
            .replace('—', '-')    // em dash
            .replace(' ', ' ')    // non-breaking space
            .replace(INVISIBLE_FORMATTING, "")
            .trim()
    }

    // A media file extension at the very end of the string. DERIVED from
    // MediaClassifier.RAW_MEDIA_EXTENSIONS so cleanFileTitle strips EXACTLY the
    // extensions looksLikeRawMediaFilename flags — otherwise a flagged-but-
    // unstripped ext (e.g. .mpg) loops the raw-filename heal and shows "Name.ext".
    private val MEDIA_EXT = Regex(
        "\\.(" + MediaClassifier.RAW_MEDIA_EXTENSIONS.joinToString("|") + ")$",
        RegexOption.IGNORE_CASE
    )
    // A 10-char bracketed token — Amazon/Audible ASIN shape (e.g. "[B00U7UVOTY]").
    private val BRACKET_ID = Regex("\\s*[\\[(]([A-Za-z0-9]{10})[\\])]")

    /**
     * Strip file-name cruft when a media FILE NAME has leaked in as the display
     * title — a Drive audiobook with no embedded ©nam tag falls back to its file
     * name, so the player/Last Played show e.g. "Jurassic Park: A Novel
     * [B00U7UVOTY].m4b". Removes a trailing media extension and a bracketed
     * ASIN-style id (10 chars containing BOTH a letter and a digit), but leaves
     * legitimate bracketed words like "[Live]" or "[Remastered]" alone.
     * Idempotent; returns the input unchanged if cleaning would empty it.
     */
    fun cleanFileTitle(s: String): String {
        if (s.isEmpty()) return s
        var out = MEDIA_EXT.replace(s.trim(), "")
        out = BRACKET_ID.replace(out) { m ->
            val inner = m.groupValues[1]
            if (inner.any { it.isDigit() } && inner.any { it.isLetter() }) "" else m.value
        }
        out = out.replace(Regex("\\s{2,}"), " ").trim()
        return out.ifBlank { s }
    }

    /**
     * Compare two strings using the locale collator after normalization.
     * Stable, null-safe, and gives correct ordering for accented and
     * non-Latin titles.
     */
    fun compare(a: String?, b: String?, locale: Locale = Locale.getDefault()): Int {
        val na = normalize(a.orEmpty())
        val nb = normalize(b.orEmpty())
        return collator(locale).compare(na, nb)
    }
}
