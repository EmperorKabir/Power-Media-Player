package com.powermediaplayer.playlist

import android.net.Uri
import com.powermediaplayer.ui.library.MediaFileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartPlaylistResolverTest {

    private fun file(
        id: Long, title: String, artist: String = "", album: String = "",
        duration: Long = 60_000
    ): MediaFileInfo = MediaFileInfo(
        id = id,
        uri = Uri.parse("uri://$id"),
        title = title, artist = artist, album = album,
        duration = duration, mimeType = "audio/mp3", size = 0,
        dateModified = id, isVideo = false, albumArtUri = null
    )

    @Test fun string_contains_filter() {
        val files = listOf(
            file(1, "Miles Runs the Voodoo Down", artist = "Miles Davis"),
            file(2, "Pyramid Song", artist = "Radiohead"),
            file(3, "All Blues", artist = "Miles Davis")
        )
        val rules = """{"rules":[{"field":"artist","op":"contains","value":"miles"}],
            "sort":"name","limit":0}""".trimIndent()
        val res = SmartPlaylistResolver.resolve(files, rules)
        assertEquals(2, res.size)
        assertTrue(res.all { it.artist.contains("Miles", ignoreCase = true) })
    }

    @Test fun numeric_gte_with_play_count_history() {
        val files = listOf(file(1, "A"), file(2, "B"), file(3, "C"))
        val history = SmartPlaylistResolver.HistorySnapshot(
            playCount = mapOf("uri://1" to 7, "uri://2" to 2, "uri://3" to 11)
        )
        val rules = """{"rules":[{"field":"playCount","op":"gte","value":5}],
            "sort":"playCount","limit":0}""".trimIndent()
        val res = SmartPlaylistResolver.resolve(files, rules, history)
        assertEquals(2, res.size)
        // sorted by playCount desc
        assertEquals(11, history.playCount[res[0].uri.toString()])
        assertEquals(7, history.playCount[res[1].uri.toString()])
    }

    @Test fun is_favourite_filter() {
        val files = listOf(file(1, "A"), file(2, "B"))
        val rules = """{"rules":[{"field":"isFavourite","op":"eq","value":true}],
            "sort":"name","limit":0}""".trimIndent()
        val history = SmartPlaylistResolver.HistorySnapshot(
            favouriteUris = setOf("uri://2")
        )
        val res = SmartPlaylistResolver.resolve(files, rules, history)
        assertEquals(1, res.size)
        assertEquals("B", res[0].title)
    }

    @Test fun limit_clips_after_sort() {
        val files = (1..10).map { file(it.toLong(), "T$it") }
        val rules = """{"rules":[],"sort":"name","limit":3}"""
        val res = SmartPlaylistResolver.resolve(files, rules)
        assertEquals(3, res.size)
    }

    @Test fun random_sort_returns_complete_set() {
        val files = (1..10).map { file(it.toLong(), "T$it") }
        val rules = """{"rules":[],"sort":"random","limit":0}"""
        val res = SmartPlaylistResolver.resolve(files, rules)
        assertEquals(files.size, res.size)
        assertEquals(files.toSet(), res.toSet())
    }

    @Test fun garbage_json_returns_empty() {
        val res = SmartPlaylistResolver.resolve(listOf(file(1, "A")), "not json")
        assertTrue(res.isEmpty())
    }
}
