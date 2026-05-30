package com.powermediaplayer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc31 §D-REORG verification (Parts A & B predicates).
 *
 * Covers the pure search-filter logic [settingsItemMatches] and the A2
 * inventory guard [SETTINGS_ITEM_IDS] without a Compose harness.
 */
class SettingsSearchTest {

    // ── A2 — inventory guard ─────────────────────────────────────────
    @Test
    fun inventory_has_exactly_the_expected_20_unique_ids() {
        val expected = setOf(
            "playback", "crossfade",
            "library", "cloud",
            "bluetooth-car", "bt-av-offset",
            "audio-effects",
            "video", "subtitles", "autohide",
            "hue", "smart-home",
            "alarms", "webhooks", "external-control",
            "display", "font-size", "theme", "diag-log", "about"
        )
        assertEquals("no setting dropped or renamed", expected, SETTINGS_ITEM_IDS.toSet())
        assertEquals("no duplicate ids", SETTINGS_ITEM_IDS.size, SETTINGS_ITEM_IDS.toSet().size)
        assertEquals(20, SETTINGS_ITEM_IDS.size)
    }

    // ── B1 — title match ─────────────────────────────────────────────
    @Test
    fun b1_filters_by_title() {
        assertTrue(settingsItemMatches("Crossfade", listOf("fade"), "cross"))
        assertFalse(settingsItemMatches("Crossfade", listOf("fade"), "alarm"))
    }

    // ── B2 — synonym/keyword match ───────────────────────────────────
    @Test
    fun b2_synonym_hit_via_keyword() {
        // visible title is "Audio effects" yet "equalizer" must resolve it
        val title = "Audio effects"
        val keywords = listOf("eq", "equaliser", "equalizer", "reverb")
        assertTrue(settingsItemMatches(title, keywords, "equalizer"))
        assertTrue(settingsItemMatches(title, keywords, "rev"))
    }

    // ── B3 — empty query restores everything ─────────────────────────
    @Test
    fun b3_empty_query_matches_all() {
        assertTrue(settingsItemMatches("anything", emptyList(), ""))
        assertTrue(settingsItemMatches("Hue", listOf("lights"), ""))
    }

    // ── B4 — no-match query matches nothing ──────────────────────────
    @Test
    fun b4_nonsense_query_matches_nothing() {
        assertFalse(settingsItemMatches("Playback & audio focus",
            listOf("focus", "gapless", "bluetooth"), "zzzzz"))
        assertFalse(settingsItemMatches("Philips Hue",
            listOf("lights", "lighting", "philips"), "zzzzz"))
    }

    // Case-insensitivity is part of B1/B2: caller lowercases the query,
    // and the predicate lowercases title+keywords.
    @Test
    fun matching_is_case_insensitive_on_data_side() {
        assertTrue(settingsItemMatches("Bluetooth A/V sync offset",
            listOf("Bluetooth", "Sync"), "bluetooth"))
    }
}
