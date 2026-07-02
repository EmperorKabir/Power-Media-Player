package com.powermediaplayer.util

import android.content.Context
import android.net.ConnectivityManager
import com.powermediaplayer.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.first

/**
 * Single decision point for "may a LARGE file transfer run right now?".
 * Blocked = the active network is metered (Android flags cellular metered
 * regardless of the plan) AND the user has turned off Settings, Cloud,
 * "Download files on mobile data" (default on).
 *
 * Consumers: Drive offline saves, folder Download all, OfflineMediaManager,
 * podcast manual downloads and Download latest N, PodcastSyncWorker's
 * auto-download block, and DriveTagEnricher's network transfers. Streaming,
 * browsing, casting and small metadata calls are deliberately exempt.
 *
 * Fail-OPEN on any error (downloads are user-initiated actions and the
 * pre-toggle behaviour was unrestricted); the passive browse-time cover peek
 * keeps its own fail-closed check in CloudViewModel.
 */
object MobileDataPolicy {

    const val BLOCKED_MESSAGE =
        "Downloads on mobile data are turned off in Settings, Cloud."

    suspend fun downloadsBlocked(context: Context, settings: SettingsDataStore): Boolean =
        runCatching {
            isMetered(context) && !settings.downloadFilesOnMobileData.first()
        }.getOrDefault(false)

    fun isMetered(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .isActiveNetworkMetered
    }.getOrDefault(false)
}
