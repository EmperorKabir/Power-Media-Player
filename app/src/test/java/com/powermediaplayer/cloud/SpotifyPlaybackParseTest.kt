package com.powermediaplayer.cloud

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests for [parseSpotifyPlaybackState] — the /v1/me/player parse (bug 2026-08-25:
 * explicit JSON null in `context`/`item`/`device` threw ClassCastException → the caller
 * dropped the snap → the mirror CLEARED mid-playback for Spotify podcast episodes).
 */
class SpotifyPlaybackParseTest {

    private fun parse(json: String) =
        parseSpotifyPlaybackState(JsonParser.parseString(json).asJsonObject)

    @Test
    fun episodeWithNullContext_stillParses() {
        // The bug: a Saved Episode / contextless track → "context": null. Must NOT drop the snap.
        val s = parse(
            """{"is_playing":true,"progress_ms":1000,
               "device":{"name":"Phone","type":"Smartphone"},
               "item":{"name":"Ep 1","uri":"spotify:episode:abc","duration_ms":600000},
               "context":null}"""
        )
        assertNotNull("episode with context:null must parse (not clear the mirror)", s)
        assertEquals("Ep 1", s!!.title)
        assertEquals("spotify:episode:abc", s.trackUri)
        assertEquals(true, s.isPlaying)
        assertNull(s.contextUri)
        assertEquals("Smartphone", s.deviceType)
    }

    @Test
    fun nullItem_returnsNull() {
        // Ad break: "item": null → nothing playing → null snap (correct).
        val s = parse("""{"is_playing":true,"item":null,"context":null}""")
        assertNull(s)
    }

    @Test
    fun nullDeviceName_doesNotThrow() {
        val s = parse(
            """{"is_playing":true,"item":{"name":"T","uri":"spotify:track:x","duration_ms":1000},
               "device":{"name":null,"type":"Speaker"}}"""
        )
        assertNotNull(s)
        assertNull(s!!.deviceName)
        assertEquals("Speaker", s.deviceType)
    }

    @Test
    fun fullTrack_withContextAndAlbumArt() {
        val s = parse(
            """{"is_playing":true,"progress_ms":42000,
               "device":{"name":"TV","type":"CastAudio"},
               "context":{"uri":"spotify:album:xyz"},
               "item":{"name":"Song","uri":"spotify:track:t1","duration_ms":180000,
                 "artists":[{"name":"A1"},{"name":"A2"}],
                 "album":{"name":"Alb","images":[{"url":"http://img/1"}]}}}"""
        )
        assertNotNull(s)
        assertEquals("Song", s!!.title)
        assertEquals("A1, A2", s.artist)
        assertEquals("Alb", s.album)
        assertEquals("http://img/1", s.artworkUrl)
        assertEquals("spotify:album:xyz", s.contextUri)
        assertEquals(42000L, s.positionMs)
        assertEquals(180000L, s.durationMs)
    }

    @Test
    fun nullArtistEntry_skippedNotCrash() {
        val s = parse(
            """{"is_playing":true,"item":{"name":"T","uri":"spotify:track:x","duration_ms":1000,
               "artists":[null,{"name":"Real"}]}}"""
        )
        assertNotNull(s)
        assertEquals("Real", s!!.artist)
    }

    @Test
    fun emptyImages_nullArtwork() {
        val s = parse(
            """{"is_playing":false,"item":{"name":"T","uri":"spotify:track:x","duration_ms":1000,
               "album":{"name":"Alb","images":[]}}}"""
        )
        assertNotNull(s)
        assertNull(s!!.artworkUrl)
        assertEquals(false, s.isPlaying)
    }

    @Test
    fun missingOptionalFields_defaults() {
        val s = parse("""{"item":{"name":"Bare","uri":"spotify:track:b"}}""")
        assertNotNull(s)
        assertEquals(0L, s!!.positionMs)
        assertEquals(0L, s.durationMs)
        assertEquals(false, s.isPlaying)
        assertNull(s.deviceName)
        assertEquals("", s.album)
    }
}
