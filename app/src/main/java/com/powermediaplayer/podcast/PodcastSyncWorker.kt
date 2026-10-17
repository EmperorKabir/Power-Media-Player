package com.powermediaplayer.podcast

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.powermediaplayer.data.db.dao.PodcastDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * §C10 — periodic feed refresh. Fetches every subscribed RSS, upserts
 * any new episodes. Runs every 6 h on any network. Idempotent: episodes
 * are keyed by guid + REPLACE on conflict, so re-runs don't duplicate.
 * PH10: the per-show work lives in [PodcastFeedRefresher] (shared with the
 * on-visit and manual refreshes) — this worker only schedules + reports.
 */
@HiltWorker
class PodcastSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val podcastDao: PodcastDao,
    private val refresher: PodcastFeedRefresher
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // CoroutineWorker defaults to Dispatchers.Default — blocking OkHttp
        // fetches were occupying the CPU pool (audit 5.5). IO dispatcher;
        // the refresher bounds parallelism at 3 feeds.
        val shows = podcastDao.observeShows().first()
        val summary = refresher.refreshShows(shows)
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "Podcast sync: ${shows.size} feed(s), ${summary.checked} checked, " +
                "${summary.failed} failed, ${summary.skipped} skipped, " +
                "${summary.fresh} new episode(s), ${summary.downloaded} audio downloaded"
        )
        com.powermediaplayer.diag.DiagLog.bg(
            "podcast-sync finished reason=workComplete feeds=${shows.size} " +
                "checked=${summary.checked} failed=${summary.failed} fresh=${summary.fresh}"
        )
        Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "podcast_sync_periodic_v1"

        fun enqueueIfNeeded(context: Context) {
            val req = PeriodicWorkRequestBuilder<PodcastSyncWorker>(
                6, TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder()
                    // CONNECTED (Wi-Fi OR mobile data) per explicit user
                    // directive (2026-06-26): "no need to condition it …
                    // wifi and mobile data … most seamless experience".
                    // Only requirement is that a network is present.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
            // Audit §4.1/B-4: KEEP froze the spec of whichever build first enqueued
            // this unique name — devices that ran the old Wi-Fi-only (UNMETERED)
            // build never received the 2026-06-26 CONNECTED directive. UPDATE
            // applies the current spec while PRESERVING the enqueue time/period
            // (no schedule reset on every app start; idempotent when unchanged),
            // so future constraint edits propagate to upgraders automatically.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}
