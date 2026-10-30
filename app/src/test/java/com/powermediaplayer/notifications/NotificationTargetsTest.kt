package com.powermediaplayer.notifications

import android.app.NotificationManager
import android.content.Context
import com.powermediaplayer.MainActivity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.offline.OfflineDownloadNotifier
import com.powermediaplayer.podcast.PodcastNotifier
import com.powermediaplayer.widget.NowPlayingWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 2026-10-30 (user): every notification's tap must open its OWN section of the app. The
 * offline "Saved offline" notification had no tap action at all (video issue 1); the others
 * opened the app on whatever tab was last used. Each case asserts the launch intent carried by
 * the notification's content PendingIntent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationTargetsTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val nm: NotificationManager
        get() = ctx.getSystemService(NotificationManager::class.java)

    private fun postedIntentExtras(id: Int): android.os.Bundle {
        val n = shadowOf(nm).getNotification(id)
        assertNotNull("notification $id posted", n)
        val pi = n.contentIntent
        assertNotNull("notification $id has a tap action", pi)
        val saved = shadowOf(pi).savedIntent
        assertEquals(MainActivity::class.java.name, saved.component?.className)
        assertReusesOpenActivity(saved)
        return saved.extras!!
    }

    /**
     * CLEAR_TOP|SINGLE_TOP: an OPEN app receives the tap via onNewIntent instead of being
     * recreated (the recreate let the finishing instance tear down the player connection —
     * see OwnerEpoch). Same flag pair the Spotify handoff uses.
     */
    private fun assertReusesOpenActivity(i: android.content.Intent) {
        val want = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals("CLEAR_TOP|SINGLE_TOP on the launch intent", want, i.flags and want)
    }

    private fun idFor(base: Int, uri: String) = base + (uri.hashCode() and 0xFFFF)

    @Test fun savedOfflineOpensTheLibraryDownloadedSection() {
        OfflineDownloadNotifier.completed(ctx, "drive://file-1", "A book")
        val extras = postedIntentExtras(idFor(200000, "drive://file-1"))
        assertEquals(NowPlayingWidgetProvider.TAB_LIBRARY, extras.getString(NowPlayingWidgetProvider.EXTRA_OPEN_TAB))
    }

    @Test fun downloadFailedOpensTheCloudTabToRetry() {
        OfflineDownloadNotifier.failed(ctx, "drive://file-2", "A book", "no space")
        val extras = postedIntentExtras(idFor(200000, "drive://file-2"))
        assertEquals(NowPlayingWidgetProvider.TAB_CLOUD, extras.getString(NowPlayingWidgetProvider.EXTRA_OPEN_TAB))
    }

    @Test fun savingProgressOpensTheCloudTab() {
        val info = OfflineDownloadNotifier.progressForeground(ctx, "drive://file-3", "A book", 40)
        val pi = info.notification.contentIntent
        assertNotNull("progress notification has a tap action", pi)
        val saved = shadowOf(pi).savedIntent
        assertReusesOpenActivity(saved)
        assertEquals(NowPlayingWidgetProvider.TAB_CLOUD, saved.getStringExtra(NowPlayingWidgetProvider.EXTRA_OPEN_TAB))
    }

    @Test fun newEpisodesOpensThatShowInThePodcastsSection() {
        val show = PodcastShowEntity(
            feedUrl = "https://example.com/feed.xml", title = "Some Show", artworkUrl = null,
            description = "", lastChecked = 0L, subscribedAt = 0L, displayOrder = 0
        )
        PodcastNotifier.notifyNewEpisodes(ctx, show, 2, "Episode 2")
        val extras = postedIntentExtras(show.feedUrl.hashCode())
        assertEquals(NowPlayingWidgetProvider.TAB_CLOUD, extras.getString(NowPlayingWidgetProvider.EXTRA_OPEN_TAB))
        assertEquals(show.feedUrl, extras.getString(NowPlayingWidgetProvider.EXTRA_PODCAST_FEED))
    }
}
