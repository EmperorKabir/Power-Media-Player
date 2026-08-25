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
}
