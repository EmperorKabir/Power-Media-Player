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
import kotlinx.coroutines.flow.first

/**
 * §C10 — periodic feed refresh. Fetches every subscribed RSS, upserts
 * any new episodes. Runs every 6 h on Wi-Fi (configurable in the
 * future). Idempotent: episodes are keyed by guid + REPLACE on
 * conflict, so re-runs don't duplicate.
 */
@HiltWorker
class PodcastSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val podcastDao: PodcastDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val parser = RssFeedParser()
        val shows = podcastDao.observeShows().first()
        var totalNew = 0
        shows.forEach { show ->
            val parsed = runCatching { parser.fetch(show.feedUrl) }.getOrNull()
                ?: return@forEach
            val (refreshed, episodes) = parsed
            podcastDao.upsertShow(refreshed.copy(subscribedAt = show.subscribedAt))
            podcastDao.upsertEpisodes(episodes)
            totalNew += episodes.size
        }
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "Podcast sync: ${shows.size} feed(s), $totalNew episode(s) upserted"
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "podcast_sync_periodic_v1"

        fun enqueueIfNeeded(context: Context) {
            val req = PeriodicWorkRequestBuilder<PodcastSyncWorker>(
                6, TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
