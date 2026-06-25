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
}
