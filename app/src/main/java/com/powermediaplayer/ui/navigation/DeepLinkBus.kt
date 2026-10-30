package com.powermediaplayer.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Notification deep-link targets below the tab level (2026-10-30, user: "any and all
 * notifications tapped on go to their relevant section in the app, not just the same place").
 * The tab itself travels in the launch intent as [com.powermediaplayer.widget.NowPlayingWidgetProvider.EXTRA_OPEN_TAB]
 * (player / library / cloud) and is routed by AppNavigation; the finer target is parked here
 * by MainActivity so the screen that owns it can scroll/expand once it is composed, then
 * consume it. Process-static like the service's companion flows; a cold start, a warm start
 * and an already-open app all pass through the same two hops.
 */
object DeepLinkBus {
    private val _podcastFeed = MutableStateFlow<String?>(null)

    /** A subscribed show's feed url to reveal (Cloud tab → Podcasts → that show expanded). */
    val podcastFeed: StateFlow<String?> = _podcastFeed

    fun revealPodcast(feedUrl: String) { _podcastFeed.value = feedUrl }

    fun consumePodcast(feedUrl: String) {
        if (_podcastFeed.value == feedUrl) _podcastFeed.value = null
    }
}
