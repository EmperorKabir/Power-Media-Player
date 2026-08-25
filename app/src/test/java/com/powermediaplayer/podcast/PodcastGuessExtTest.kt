package com.powermediaplayer.podcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [guessAudioExt] (bug 2026-08-25: extension taken from the whole URL incl.
 *  a dotted query value → wrong ext/MIME). */
class PodcastGuessExtTest {
    @Test fun plainUrl() = assertEquals("mp3", guessAudioExt("https://cdn.x/ep.mp3"))

    @Test fun queryWithDottedValue_ignored() {
        assertEquals("mp3", guessAudioExt("https://cdn.x/ep.mp3?file=name.ext"))
        assertEquals("mp3", guessAudioExt("https://cdn.x/ep.mp3?redirect=host.com"))
    }

    @Test fun m4a_notMislabelledMp3() =
        assertEquals("m4a", guessAudioExt("https://cdn.x/ep.m4a?src=a.b"))

    @Test fun fragmentStripped() =
        assertEquals("m4a", guessAudioExt("https://cdn.x/ep.m4a#t=30"))

    @Test fun noExtension_fallsBackToMp3() {
        assertEquals("mp3", guessAudioExt("https://cdn.x/stream?id=123"))
        assertEquals("mp3", guessAudioExt("https://cdn.x/audio"))
    }

    @Test fun caseInsensitive() = assertEquals("mp3", guessAudioExt("https://cdn.x/EP.MP3"))

    @Test fun opusAndFlac() {
        assertEquals("opus", guessAudioExt("https://cdn.x/a.opus"))
        assertEquals("flac", guessAudioExt("https://cdn.x/a.flac?x=1"))
    }

    // isCompleteDownload — truncated downloads must be rejected (bug 2026-08-25).
    @Test fun completeDownload_fullLength() =
        org.junit.Assert.assertTrue(isCompleteDownload(bytes = 1000, expectedTotal = 1000))

    @Test fun truncatedDownload_rejected() =
        org.junit.Assert.assertFalse(isCompleteDownload(bytes = 600, expectedTotal = 1000))

    @Test fun zeroBytes_rejected() =
        org.junit.Assert.assertFalse(isCompleteDownload(bytes = 0, expectedTotal = 1000))

    @Test fun unknownLength_acceptsAnyPositive() {
        org.junit.Assert.assertTrue(isCompleteDownload(bytes = 500, expectedTotal = -1))
        org.junit.Assert.assertFalse(isCompleteDownload(bytes = 0, expectedTotal = -1))
    }

    @Test fun overLength_accepted() =
        // bytes >= total (rare over-read / re-declared length) is still complete.
        org.junit.Assert.assertTrue(isCompleteDownload(bytes = 1001, expectedTotal = 1000))
}
