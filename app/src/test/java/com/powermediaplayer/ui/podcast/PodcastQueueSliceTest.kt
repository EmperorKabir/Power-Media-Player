package com.powermediaplayer.ui.podcast

import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** F1 auto-advance queue logic: [episodeQueueSlice] takes the show's episodes
 *  from the tapped one forward (same order), capped, falling back to the tapped
 *  episode alone when it isn't in the list. */
class PodcastQueueSliceTest {

    private fun ep(g: String) = PodcastEpisodeEntity(
        guid = g, feedUrl = "feed", title = "T$g", audioUrl = "http://a/$g.mp3"
    )

    private val ordered = listOf(ep("1"), ep("2"), ep("3"), ep("4"), ep("5"))

    @Test fun fromFirst_returnsAll() {
        val q = episodeQueueSlice(ordered, "1", ep("1"))
        assertEquals(listOf("1", "2", "3", "4", "5"), q.map { it.guid })
    }

    @Test fun fromMiddle_returnsForward() {
        val q = episodeQueueSlice(ordered, "3", ep("3"))
        assertEquals(listOf("3", "4", "5"), q.map { it.guid })
    }

    @Test fun fromLast_returnsJustIt() {
        val q = episodeQueueSlice(ordered, "5", ep("5"))
        assertEquals(listOf("5"), q.map { it.guid })
    }

    @Test fun notInList_fallsBackToTapped() {
        val tapped = ep("99")
        val q = episodeQueueSlice(ordered, "99", tapped)
        assertEquals(listOf("99"), q.map { it.guid })
    }

    @Test fun capLimitsLength() {
        val big = (1..200).map { ep(it.toString()) }
        val q = episodeQueueSlice(big, "1", ep("1"), cap = 50)
        assertEquals(50, q.size)
        assertEquals("1", q.first().guid)
        assertEquals("50", q.last().guid)
    }
}
