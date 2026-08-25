package com.powermediaplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic media classification (#13 icon, #8 sub-kind, #12 thumbnail gate).
 * No Android types in the API → plain JUnit, no Robolectric.
 */
class MediaClassifierTest {

    // ---- isVideoByName: extension authoritative over container mime ----

    @Test fun m4b_in_video_container_is_not_video() =
        assertFalse(MediaClassifier.isVideoByName("Book 7.m4b", "video/mp4"))

    @Test fun m4a_in_video_container_is_not_video() =
        assertFalse(MediaClassifier.isVideoByName("song.m4a", "video/mp4"))

    @Test fun real_mp4_video_is_video() =
        assertTrue(MediaClassifier.isVideoByName("clip.mp4", "video/mp4"))

    @Test fun mkv_with_video_mime_is_video() =
        assertTrue(MediaClassifier.isVideoByName("clip.mkv", "video/x-matroska"))

    @Test fun mp3_audio_is_not_video() =
        assertFalse(MediaClassifier.isVideoByName("track.mp3", "audio/mpeg"))

    @Test fun unknown_extension_falls_back_to_mime() {
        assertTrue(MediaClassifier.isVideoByName("movie", "video/mp4"))
        assertFalse(MediaClassifier.isVideoByName("noext", "audio/mpeg"))
    }

    @Test fun extension_match_is_case_insensitive() {
        assertFalse(MediaClassifier.isVideoByName("BOOK.M4B", "VIDEO/MP4"))
        assertTrue(MediaClassifier.isVideoByName("CLIP.MP4", "VIDEO/MP4"))
    }

    // ---- firstNonRawTitle: heal a raw title from a clean sibling ----

    @Test fun firstNonRawTitle_prefers_clean_over_raw_filename() =
        assertEquals(
            "Harry Potter and the Philosopher's Stone (Full-Cast Edition)",
            MediaClassifier.firstNonRawTitle(
                listOf(
                    "Harry Potter and the Philosopher's Stone (Full-Cast Edition) [B0F14RFHS6].m4b",
                    "Harry Potter and the Philosopher's Stone (Full-Cast Edition)"
                )
            )
        )

    @Test fun firstNonRawTitle_null_when_all_raw_or_blank() {
        assertEquals(null, MediaClassifier.firstNonRawTitle(listOf("book.m4b", "  ", null, "clip.mp4")))
        assertEquals(null, MediaClassifier.firstNonRawTitle(emptyList()))
    }

    @Test fun firstNonRawTitle_keeps_recency_order_and_trims() =
        assertEquals(
            "Real Title",
            MediaClassifier.firstNonRawTitle(listOf("a.mp3", "  Real Title  ", "Older Clean"))
        )

    // ---- classifyAudioSubKind: podcast vs audiobook vs song ----

    @Test fun podcast_membership_wins() = assertEquals(
        AudioSubKind.PODCAST,
        MediaClassifier.classifyAudioSubKind("ep-42.mp3", hasChapters = false, isPodcast = true)
    )

    @Test fun podcast_wins_even_with_chapters() = assertEquals(
        AudioSubKind.PODCAST,
        MediaClassifier.classifyAudioSubKind("ep-42.m4a", hasChapters = true, isPodcast = true)
    )

    @Test fun m4b_extension_is_an_audiobook() = assertEquals(
        AudioSubKind.AUDIOBOOK,
        MediaClassifier.classifyAudioSubKind("Book.m4b", hasChapters = false, isPodcast = false)
    )

    @Test fun chapters_make_an_audiobook() = assertEquals(
        AudioSubKind.AUDIOBOOK,
        MediaClassifier.classifyAudioSubKind("anything.mp3", hasChapters = true, isPodcast = false)
    )

    @Test fun plain_mp3_no_chapters_is_a_song() = assertEquals(
        AudioSubKind.SONG,
        MediaClassifier.classifyAudioSubKind("track.mp3", hasChapters = false, isPodcast = false)
    )

    // ---- shouldThumbnailVideo: #12 gate ----

    @Test fun mediastore_video_with_video_name_is_thumbnailed() =
        assertTrue(MediaClassifier.shouldThumbnailVideo("clip.mp4", true))

    @Test fun m4b_is_never_thumbnailed_even_if_flagged_video() =
        assertFalse(MediaClassifier.shouldThumbnailVideo("Book.m4b", true))

    @Test fun audio_row_is_never_thumbnailed() =
        assertFalse(MediaClassifier.shouldThumbnailVideo("track.mp3", false))

    // ---- looksLikeRawMediaFilename: cold-start heal-re-enrich trigger ----

    @Test fun raw_m4b_filename_with_asin_is_raw() = assertTrue(
        MediaClassifier.looksLikeRawMediaFilename(
            "Harry Potter and the Philosopher’s Stone (Full-Cast Edition) [B0F14RFHS6].m4b"
        )
    )

    @Test fun clean_embedded_title_is_not_raw() = assertFalse(
        MediaClassifier.looksLikeRawMediaFilename(
            "Harry Potter and the Philosopher’s Stone (Full-Cast Edition)"
        )
    )

    @Test fun clean_title_ending_in_parenthesis_is_not_raw() = assertFalse(
        MediaClassifier.looksLikeRawMediaFilename(
            "Harry Potter and the Philosopher’s Stone (Full-Cast Edition) (Unabridged)"
        )
    )

    @Test fun raw_video_and_audio_extensions_are_raw() {
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("My Movie.mp4"))
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("clip.mkv"))
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("song.mp3"))
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("audio.flac"))
    }

    @Test fun raw_filename_extension_is_case_insensitive() =
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("BOOK.M4B"))

    @Test fun url_derived_name_with_query_strips_before_testing_extension() {
        // a URL-shaped name keeps the media extension testable past ?query/#frag
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("file.m4b?token=abc#frag"))
        // a clean title carrying a stray query must not false-positive
        assertFalse(MediaClassifier.looksLikeRawMediaFilename("The Hobbit (Unabridged)?x=1"))
    }

    @Test fun null_or_blank_title_is_not_raw() {
        assertFalse(MediaClassifier.looksLikeRawMediaFilename(null))
        assertFalse(MediaClassifier.looksLikeRawMediaFilename("   "))
    }

    // Regression (bug 2026-08-25): a plain filename whose NAME contains a literal
    // '#' or '?' before the dot was wrongly classified NOT-raw (extension stripped
    // away with the '#'/'?'), so the embedded-title heal never fired.
    @Test fun raw_filename_with_literal_hash_in_name_is_raw() {
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("Track #1.mp3"))
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("Vol #3.m4b"))
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("file#section.m4b"))
    }

    @Test fun raw_filename_with_literal_question_in_name_is_raw() =
        assertTrue(MediaClassifier.looksLikeRawMediaFilename("Who Was Einstein?.m4b"))

    @Test fun firstNonRawTitle_skips_hash_filename_and_picks_embedded() =
        assertEquals(
            "Real Embedded Title",
            MediaClassifier.firstNonRawTitle(listOf("Track #1.mp3", "Real Embedded Title"))
        )

    @Test fun title_with_no_extension_is_not_raw() =
        assertFalse(MediaClassifier.looksLikeRawMediaFilename("Just A Plain Title"))
}
