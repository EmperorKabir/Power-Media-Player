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

    /** Collator for the user's current locale. PRIMARY strength → case + accent insensitive. */
    fun collator(locale: Locale = Locale.getDefault()): Collator =
        Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

    /**
     * Repair "mojibake" — UTF-8 bytes mistakenly decoded as Latin-1.
     * Common in MP4 metadata read by ExoPlayer when the encoding marker
     * is wrong: â€™ → ', â€" → —, Ã© → é, etc. Idempotent: returns the
     * original string when re-decoding would lose information.
     */
    fun fixMojibake(s: String): String {
        if (s.isEmpty()) return s
        if (!s.any { it.code in 0x80..0xFF }) return s  // No Latin-1 high bytes — already clean
        return try {
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            val candidate = String(bytes, Charsets.UTF_8)
            // Heuristic: prefer the candidate iff it strictly reduces the
            // count of high-codepoint chars without producing replacement
            // markers. Keeps legitimately Latin-1-only strings alone.
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
