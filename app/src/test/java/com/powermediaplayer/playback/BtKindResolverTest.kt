package com.powermediaplayer.playback

import com.powermediaplayer.data.preferences.BtMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** M1/M2 — BT media-kind resolution (audit F2: chapters beat the http heuristic). */
class BtKindResolverTest {

    @Test fun videoWins() {
        assertEquals(BtMediaKind.VIDEO, BtKindResolver.resolve(isVideo = true, hasChapters = true, isPodcast = true))
    }

    @Test fun chaptersMeanAudiobook_evenOverHttpPodcastHeuristic() {
        // A Drive-streamed .m4b: http URI (isPodcast=true) BUT has chapters →
        // must be AUDIOBOOK so BT chapter nav works on the cloud/car path.
        assertEquals(
            BtMediaKind.AUDIOBOOK,
            BtKindResolver.resolve(isVideo = false, hasChapters = true, isPodcast = true)
        )
    }

    @Test fun httpNoChapters_isPodcast() {
        assertEquals(
            BtMediaKind.PODCAST,
            BtKindResolver.resolve(isVideo = false, hasChapters = false, isPodcast = true)
        )
    }

    @Test fun plainAudio_isMusic() {
        assertEquals(
            BtMediaKind.MUSIC,
            BtKindResolver.resolve(isVideo = false, hasChapters = false, isPodcast = false)
        )
    }
}
