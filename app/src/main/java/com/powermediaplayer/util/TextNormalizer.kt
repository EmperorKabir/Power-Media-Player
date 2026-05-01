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
     * Returns a normalized form of [s] suitable for display, search, and
     * comparison. Idempotent — re-normalizing produces the same output.
     */
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        val nfc = Normalizer.normalize(s, Normalizer.Form.NFC)
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
