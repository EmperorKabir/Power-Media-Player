package com.powermediaplayer.ui.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeepLinkBusTest {
    @Test fun openUriIsParkedThenConsumedOnce() {
        val u = Uri.parse("content://media/external/audio/media/42")
        DeepLinkBus.requestOpen(u)
        assertTrue(DeepLinkBus.hasPendingOpen())
        assertEquals(u, DeepLinkBus.openUri.value)
        DeepLinkBus.consumeOpen(u)
        assertFalse(DeepLinkBus.hasPendingOpen())
        assertNull(DeepLinkBus.openUri.value)
    }

    @Test fun consumingAStaleUriDoesNotDropANewerOne() {
        val a = Uri.parse("content://a"); val b = Uri.parse("content://b")
        DeepLinkBus.requestOpen(a); DeepLinkBus.requestOpen(b)
        DeepLinkBus.consumeOpen(a)
        assertEquals(b, DeepLinkBus.openUri.value)
        DeepLinkBus.consumeOpen(b)
    }

    @Test fun podcastFeedConsumeIsExactMatch() {
        DeepLinkBus.revealPodcast("https://x/feed")
        DeepLinkBus.consumePodcast("https://y/feed")
        assertEquals("https://x/feed", DeepLinkBus.podcastFeed.value)
        DeepLinkBus.consumePodcast("https://x/feed")
        assertNull(DeepLinkBus.podcastFeed.value)
    }
}
