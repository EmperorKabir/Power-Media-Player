package com.powermediaplayer.podcast

import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PH10/PH3 pure helpers behind the shared feed refresher. */
class PodcastFeedRefresherTest {

    private val feed = "https://feeds.example.com/show"

    private fun stored() = PodcastShowEntity(
        feedUrl = feed, title = "Stored title", artworkUrl = "https://img/old.jpg",
        description = "old desc", lastChecked = 1_000L, subscribedAt = 500L,
        autoDownload = true, retentionLastN = 3, notifyOnNewEpisode = true,
        downloadTreeUri = "content://tree/x", displayOrder = 7
    )

    private fun parsed(title: String = "Parsed title", art: String? = "https://img/new.jpg", desc: String = "new desc") =
        PodcastShowEntity(feedUrl = feed, title = title, artworkUrl = art, description = desc, lastChecked = 2_000L)

    @Test fun mergeShowRefresh_keepsEveryUserOwnedField_andTakesFreshMetadata() {
        val m = mergeShowRefresh(stored(), parsed())
        assertEquals(7, m.displayOrder)          // the bug: the worker reset this to 0
        assertEquals(500L, m.subscribedAt)
        assertEquals(true, m.autoDownload)
        assertEquals(3, m.retentionLastN)
        assertEquals(true, m.notifyOnNewEpisode)
        assertEquals("content://tree/x", m.downloadTreeUri)
        assertEquals("Parsed title", m.title)
        assertEquals("https://img/new.jpg", m.artworkUrl)
        assertEquals("new desc", m.description)
        assertEquals(2_000L, m.lastChecked)      // the fetch time advances
    }

    @Test fun mergeShowRefresh_blankOrUrlTitle_keepsStoredTitle() {
        assertEquals("Stored title", mergeShowRefresh(stored(), parsed(title = "")).title)
        assertEquals("Stored title", mergeShowRefresh(stored(), parsed(title = feed)).title)
    }

    @Test fun mergeShowRefresh_missingArtworkOrDescription_keepsStored() {
        val m = mergeShowRefresh(stored(), parsed(art = null, desc = ""))
        assertEquals("https://img/old.jpg", m.artworkUrl)
        assertEquals("old desc", m.description)
    }

    private fun ep(g: String, played: Boolean = false, local: String? = null) = PodcastEpisodeEntity(
        guid = g, feedUrl = feed, title = "T$g", audioUrl = "https://a/$g.mp3",
        isPlayed = played, localPath = local, localBytes = if (local != null) 123L else 0L,
        downloadedAt = if (local != null) 999L else 0L
    )

    @Test fun mergeEpisodeState_carriesPlayedAndOfflineCopy_newGuidsInsertAsParsed() {
        val existing = mapOf("1" to ep("1", played = true, local = "file:///e1.mp3"), "2" to ep("2"))
        val out = mergeEpisodeState(listOf(ep("1"), ep("2"), ep("3")), existing)
        assertEquals(true, out[0].isPlayed)
        assertEquals("file:///e1.mp3", out[0].localPath)
        assertEquals(123L, out[0].localBytes)
        assertEquals(999L, out[0].downloadedAt)
        assertEquals(false, out[1].isPlayed)
        assertNull(out[2].localPath)
        assertEquals(listOf("1", "2", "3"), out.map { it.guid })
    }

    @Test fun staleShows_onlyThoseOlderThanThrottle_neverCheckedCountsAsStale() {
        val now = 10_000_000L
        val fresh = stored().copy(feedUrl = "f1", lastChecked = now - 5 * 60_000L)
        val old = stored().copy(feedUrl = "f2", lastChecked = now - 16 * 60_000L)
        val never = stored().copy(feedUrl = "f3", lastChecked = 0L)
        val edge = stored().copy(feedUrl = "f4", lastChecked = now - 15 * 60_000L)
        assertEquals(listOf("f2", "f3", "f4"), staleShows(listOf(fresh, old, never, edge), now).map { it.feedUrl })
    }

    @Test fun formatCheckedAgo_buckets() {
        val now = 1_000_000_000_000L
        assertEquals("Not checked yet", formatCheckedAgo(0L, now))
        assertEquals("Checked just now", formatCheckedAgo(now - 30_000L, now))
        assertEquals("Checked 12 min ago", formatCheckedAgo(now - 12 * 60_000L, now))
        assertEquals("Checked 3 h ago", formatCheckedAgo(now - 3 * 3_600_000L, now))
        assertEquals("Checked 2 d ago", formatCheckedAgo(now - 50 * 3_600_000L, now))
        assertEquals("Checked just now", formatCheckedAgo(now + 5_000L, now)) // clock skew never negative
    }

    @Test fun placeholderShow_usesHintThenHostThenUrl() {
        val a = placeholderShow(feed, "Hit title", "https://img/a.jpg", 4, 123L)
        assertEquals("Hit title", a.title); assertEquals("https://img/a.jpg", a.artworkUrl)
        assertEquals(4, a.displayOrder); assertEquals(0L, a.lastChecked); assertEquals(123L, a.subscribedAt)
        val b = placeholderShow(feed, null, "", 1, 1L)
        assertEquals("feeds.example.com", b.title); assertNull(b.artworkUrl)
        val c = placeholderShow("not a url", "", null, 1, 1L)
        assertEquals("not a url", c.title)
    }
}
