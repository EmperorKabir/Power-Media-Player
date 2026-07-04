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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
    private val podcastDao: PodcastDao,
    private val settings: com.powermediaplayer.data.preferences.SettingsDataStore
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // CoroutineWorker defaults to Dispatchers.Default — blocking OkHttp
        // fetches were occupying the CPU pool (audit 5.5). IO dispatcher +
        // bounded parallelism: feeds are independent; 3-wide keeps radio
        // and server load sane while a 20-show library stops being serial.
        val parser = RssFeedParser()
        val downloader = com.powermediaplayer.podcast.PodcastDownloader(applicationContext)
        val globalTree = settings.podcastDownloadTreeUri.first().ifBlank { null }
        // Feed refresh (small XML) always runs on any connection, preserving the
        // 2026-06-26 "seamless" directive; only the EPISODE AUDIO downloads honour
        // Settings, Cloud, "Download files on mobile data". Blocked episodes are
        // picked up by the next sync on an unmetered network (skip-if-present).
        val downloadsAllowed = !com.powermediaplayer.util.MobileDataPolicy
            .downloadsBlocked(applicationContext, settings)
        val shows = podcastDao.observeShows().first()
        val sem = Semaphore(3)
        val results = coroutineScope {
            shows.map { show ->
                async {
                    sem.withPermit {
                        runCatching {
                            syncShow(parser, downloader, show, globalTree, downloadsAllowed)
                        }.getOrDefault(0 to 0)
                    }
                }
            }.map { it.await() }
        }
        val totalNew = results.sumOf { it.first }
        val totalDownloaded = results.sumOf { it.second }
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "Podcast sync: ${shows.size} feed(s), $totalNew episode(s) upserted, " +
                "$totalDownloaded audio downloaded"
        )
        com.powermediaplayer.diag.DiagLog.bg(
            "podcast-sync finished reason=workComplete feeds=${shows.size}"
        )
        Result.success()
    }

    /** Returns (episodesUpserted, audioFilesDownloaded) for one show. */
    private suspend fun syncShow(
        parser: RssFeedParser,
        downloader: PodcastDownloader,
        show: com.powermediaplayer.data.db.entity.PodcastShowEntity,
        globalTree: String?,
        downloadsAllowed: Boolean
    ): Pair<Int, Int> {
        val parsed = runCatching { parser.fetch(show.feedUrl) }.getOrNull()
            ?: return 0 to 0
        val (refreshed, episodes) = parsed
        // Preserve user-set per-show settings + subscribedAt.
        podcastDao.upsertShow(
            refreshed.copy(
                subscribedAt = show.subscribedAt,
                autoDownload = show.autoDownload,
                retentionLastN = show.retentionLastN,
                notifyOnNewEpisode = show.notifyOnNewEpisode,
                // Preserve the user's per-show download folder — a REPLACE upsert
                // with the freshly-parsed (null) value would silently reset it
                // to the global default on every periodic sync.
                downloadTreeUri = show.downloadTreeUri
            )
        )
        podcastDao.syncEpisodes(episodes)
        var downloaded = 0
        // §C10 auto-download — when the user opted in, fetch the
        // newest N episodes' audio files into the spec'd folder.
        if (show.autoDownload && downloadsAllowed) {
            val budget = if (show.retentionLastN > 0) show.retentionLastN else 5
            // Re-read so localPath set by an earlier sync is visible (skip-if-present).
            val stored = podcastDao.observeEpisodes(show.feedUrl).first()
            val byGuid = stored.associateBy { it.guid }
            val newest = episodes.sortedByDescending { it.publishedAt }.take(budget)
            newest.forEach { parsedEp ->
                val ep = byGuid[parsedEp.guid] ?: parsedEp
                val saved = downloader.downloadIfMissing(show, ep, globalTree)
                if (saved != null) {
                    podcastDao.setLocalPath(
                        ep.guid, saved.uri, saved.bytes, System.currentTimeMillis()
                    )
                    downloaded++
                }
            }
        }
        return episodes.size to downloaded
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
