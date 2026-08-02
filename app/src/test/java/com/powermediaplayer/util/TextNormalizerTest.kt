package com.powermediaplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the [TextNormalizer] heal-loop contract (audit TEST GAP). These pure
 * functions sit under the recurring "title reverts to the raw filename" +
 * "mojibake in metadata" bug classes; the load-bearing invariant is that
 * [TextNormalizer.cleanFileTitle] strips EXACTLY the extensions
 * [MediaClassifier.looksLikeRawMediaFilename] flags — otherwise the raw-title
 * heal re-fires forever and the UI shows "Name.ext".
 */
class TextNormalizerTest {

    // ── fixMojibake ───────────────────────────────────────────────────────────

    @Test fun mojibake_repairs_classic_utf8_as_cp1252_apostrophe() {
        // U+2019 (’) mis-decoded as CP1252 becomes the 3-char "â€™"; fixMojibake
        // restores the ORIGINAL curly apostrophe (mapping curly→ASCII is normalize's job).
        assertEquals("don’t", TextNormalizer.fixMojibake("donâ€™t"))
    }

    @Test fun normalize_unmojibakes_then_maps_curly_apostrophe_to_ascii() {
        assertEquals("don't", TextNormalizer.normalize("donâ€™t"))
    }

    @Test fun mojibake_quick_rejects_string_with_no_markers() {
        val clean = "A Gentleman in Moscow"
        assertEquals(clean, TextNormalizer.fixMojibake(clean))
    }

    @Test fun mojibake_is_idempotent() {
        val once = TextNormalizer.fixMojibake("donâ€™t")
        assertEquals(once, TextNormalizer.fixMojibake(once))
    }

    @Test fun mojibake_does_not_corrupt_a_legit_accented_word_that_contains_a_marker() {
        // 'â' is a mojibake marker, but "château" is genuine text: re-decoding it
        // as UTF-8 yields a replacement char, so the guard must return it UNCHANGED.
        assertEquals("château", TextNormalizer.fixMojibake("château"))
    }

    @Test fun mojibake_empty_stays_empty() {
        assertEquals("", TextNormalizer.fixMojibake(""))
    }

    // ── cleanFileTitle ────────────────────────────────────────────────────────

    @Test fun clean_strips_trailing_media_ext_and_asin_bracket() {
        assertEquals(
            "Jurassic Park: A Novel",
            TextNormalizer.cleanFileTitle("Jurassic Park: A Novel [B00U7UVOTY].m4b")
        )
    }

    @Test fun clean_keeps_short_legit_bracket() {
        assertEquals("Song [Live]", TextNormalizer.cleanFileTitle("Song [Live].mp3"))
    }

    @Test fun clean_keeps_ten_char_all_letter_bracket() {
        // "[Remastered]" is 10 chars but has no digit → not an ASIN → kept.
        assertEquals("Track [Remastered]", TextNormalizer.cleanFileTitle("Track [Remastered]"))
    }

    @Test fun clean_is_idempotent() {
        val once = TextNormalizer.cleanFileTitle("Jurassic Park: A Novel [B00U7UVOTY].m4b")
        assertEquals(once, TextNormalizer.cleanFileTitle(once))
    }

    @Test fun clean_returns_original_when_cleaning_would_empty_it() {
        val onlyCruft = "[B00U7UVOTY].m4b"
        assertEquals(onlyCruft, TextNormalizer.cleanFileTitle(onlyCruft))
    }

    // ── heal-loop contract: cleanFileTitle undoes EXACTLY what flags raw ────────

    @Test fun clean_defeats_looksLikeRaw_for_every_raw_extension_no_heal_loop() {
        for (ext in MediaClassifier.RAW_MEDIA_EXTENSIONS) {
            val raw = "My Great Title.$ext"
            assertTrue(
                "expected '$raw' to be flagged raw",
                MediaClassifier.looksLikeRawMediaFilename(raw)
            )
            val cleaned = TextNormalizer.cleanFileTitle(raw)
            assertFalse(
                "cleanFileTitle('$raw') = '$cleaned' still flags raw → heal loops",
                MediaClassifier.looksLikeRawMediaFilename(cleaned)
            )
        }
    }

    @Test fun firstNonRawTitle_skips_the_filename_and_picks_the_real_title() {
        val picked = MediaClassifier.firstNonRawTitle(
            listOf("", "Deathly Hallows.m4b", "Harry Potter and the Deathly Hallows")
        )
        assertEquals("Harry Potter and the Deathly Hallows", picked)
    }
}
