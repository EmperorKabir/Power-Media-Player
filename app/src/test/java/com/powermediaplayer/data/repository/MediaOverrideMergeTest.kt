package com.powermediaplayer.data.repository

import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Podcast override resolution: episode → show → global. [mergeEpisodeOverShow]
 * merges the per-episode row OVER the per-show row; a NULL axis falls through to
 * the show, then (returned NULL) to the global setting downstream.
 */
class MediaOverrideMergeTest {

    private val uri = "https://feed/ep1.mp3"

    @Test
    fun bothNull_returnsNull() {
        assertNull(mergeEpisodeOverShow(uri, null, null))
    }

    @Test
    fun bothEmptyRows_returnNull() {
        // non-null rows that set NO axis must collapse to null (→ global)
        val ep = MediaOverrideEntity(mediaUri = uri)
        val show = MediaOverrideEntity(mediaUri = "feed")
        assertNull(mergeEpisodeOverShow(uri, ep, show))
    }

    @Test
    fun onlyShow_appliesToEpisode() {
        val show = MediaOverrideEntity(mediaUri = "feed", playbackSpeed = 1.5f)
        val merged = mergeEpisodeOverShow(uri, null, show)
        assertEquals(1.5f, merged?.playbackSpeed)
        assertEquals(uri, merged?.mediaUri) // re-keyed to the episode uri
    }

    @Test
    fun episodeWins_perAxis() {
        val ep = MediaOverrideEntity(mediaUri = uri, playbackSpeed = 2.0f)
        val show = MediaOverrideEntity(mediaUri = "feed", playbackSpeed = 1.5f)
        assertEquals(2.0f, mergeEpisodeOverShow(uri, ep, show)?.playbackSpeed)
    }

    @Test
    fun unionOfDistinctAxes() {
        // episode sets speed, show sets boost → merged carries BOTH
        val ep = MediaOverrideEntity(mediaUri = uri, playbackSpeed = 1.2f)
        val show = MediaOverrideEntity(mediaUri = "feed", volumeBoostMb = 600)
        val merged = mergeEpisodeOverShow(uri, ep, show)
        assertEquals(1.2f, merged?.playbackSpeed)
        assertEquals(600, merged?.volumeBoostMb)
    }

    @Test
    fun episodeNullAxis_fallsThroughToShow() {
        // episode overrides only speed; show's monoMix must still apply
        val ep = MediaOverrideEntity(mediaUri = uri, playbackSpeed = 1.2f)
        val show = MediaOverrideEntity(mediaUri = "feed", monoMix = true)
        val merged = mergeEpisodeOverShow(uri, ep, show)
        assertEquals(true, merged?.monoMix)
        assertEquals(1.2f, merged?.playbackSpeed)
    }
}
