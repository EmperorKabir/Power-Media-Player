package com.powermediaplayer.podcast

import android.content.Context
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * PH10 — the ONE place a subscribed feed is re-fetched. Before this the only
 * refresh was the 6-hourly [PodcastSyncWorker] (the Podcasts header text
 * promised "refresh on each visit" and nothing did it, and there was no manual
 * refresh), and the worker's REPLACE upsert dropped the user-set displayOrder
 * every run. Used by: the periodic worker, the on-visit throttled check, the
 * header / per-show refresh buttons.
 *
 * Per show: fresh fetch (cache revalidated, never served stale), show row
 * upsert preserving every user-owned field, episode sync preserving per-
 * episode state, "new episode" notification (never on first population),
 * auto-download of the newest N when the show opts in and the data policy
 * allows. A feed already in flight is skipped, so a visit landing during the
 * worker run cannot double-fetch it.
 */
@Singleton
class PodcastFeedRefresher @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val podcastDao: PodcastDao,
    private val settings: SettingsDataStore
) {
    private val parser = RssFeedParser()
    private val downloader = PodcastDownloader(appContext)

    sealed interface Outcome {
        /** [fresh] = episodes not stored before this refresh. */
        data class Ok(val total: Int, val fresh: Int, val downloaded: Int) : Outcome
        data class Failed(val reason: String) : Outcome
        data object Skipped : Outcome
    }

    data class Summary(
        val checked: Int,
        val failed: Int,
        val skipped: Int,
        val fresh: Int,
        val downloaded: Int
    )

    private val inFlightLock = Mutex()
    private val inFlight = HashSet<String>()
    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    /** Feed urls with a refresh in flight (drives the UI spinners). */
    val refreshing: StateFlow<Set<String>> = _refreshing

    private suspend fun claim(feedUrl: String): Boolean = inFlightLock.withLock {
        if (!inFlight.add(feedUrl)) return false
        _refreshing.value = inFlight.toSet()
        true
    }

    private suspend fun release(feedUrl: String) = inFlightLock.withLock {
        inFlight.remove(feedUrl)
        _refreshing.value = inFlight.toSet()
    }

    /** Refresh [shows] 3-wide (feeds are independent; bounded radio/server load). */
    suspend fun refreshShows(shows: List<PodcastShowEntity>): Summary {
        if (shows.isEmpty()) return Summary(0, 0, 0, 0, 0)
        val globalTree = settings.podcastDownloadTreeUri.first().ifBlank { null }
        // Feed XML always fetches on any network; only episode AUDIO honours
        // Settings, Cloud, "Download files on mobile data".
        val downloadsAllowed = !com.powermediaplayer.util.MobileDataPolicy
            .downloadsBlocked(appContext, settings)
        val sem = Semaphore(3)
        val outcomes = coroutineScope {
            shows.map { show ->
                async {
                    sem.withPermit { refreshShow(show, globalTree, downloadsAllowed) }
                }
            }.map { it.await() }
        }
        return Summary(
            checked = outcomes.count { it is Outcome.Ok },
            failed = outcomes.count { it is Outcome.Failed },
            skipped = outcomes.count { it is Outcome.Skipped },
            fresh = outcomes.filterIsInstance<Outcome.Ok>().sumOf { it.fresh },
            downloaded = outcomes.filterIsInstance<Outcome.Ok>().sumOf { it.downloaded }
        )
    }

    suspend fun refreshShow(
        show: PodcastShowEntity,
        globalTree: String?,
        downloadsAllowed: Boolean
    ): Outcome {
        if (!claim(show.feedUrl)) return Outcome.Skipped
        val t0 = System.currentTimeMillis()
        try {
            val outcome = doRefresh(show, globalTree, downloadsAllowed)
            val took = System.currentTimeMillis() - t0
            com.powermediaplayer.diag.DiagLog.bg(
                "podcast-refresh feed=${com.powermediaplayer.diag.DiagLog.hash(show.feedUrl)} " +
                    "outcome=$outcome tookMs=$took"
            )
            return outcome
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "podcast refresh failed: ${t.message}", t)
            return Outcome.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            release(show.feedUrl)
        }
    }

    private suspend fun doRefresh(
        show: PodcastShowEntity,
        globalTree: String?,
        downloadsAllowed: Boolean
    ): Outcome {
        val fetched = when (val r = parser.fetchResult(show.feedUrl, fresh = true)) {
            is RssFeedParser.FetchResult.Ok -> r
            is RssFeedParser.FetchResult.HttpError -> return Outcome.Failed("HTTP ${r.code}")
            is RssFeedParser.FetchResult.NotFeed -> return Outcome.Failed("not a feed (${r.reason})")
            RssFeedParser.FetchResult.Network -> return Outcome.Failed("network")
        }
        // Re-read the row: the user may have changed a setting / reordered while
        // the fetch ran; the stale snapshot would overwrite it.
        val current = podcastDao.getShow(show.feedUrl) ?: show
        podcastDao.upsertShow(mergeShowRefresh(current, fetched.show))
        val preGuids = podcastDao.episodeGuidsForFeed(show.feedUrl).toHashSet()
        podcastDao.syncEpisodes(fetched.episodes)
        val freshEpisodes = fetched.episodes.filter { it.guid !in preGuids }
        // First-ever population (empty pre set) never notifies: that would fire
        // for the whole back catalogue on subscribe.
        if (current.notifyOnNewEpisode && preGuids.isNotEmpty() && freshEpisodes.isNotEmpty()) {
            PodcastNotifier.notifyNewEpisodes(
                appContext, current, freshEpisodes.size,
                freshEpisodes.maxByOrNull { it.publishedAt }?.title
            )
        }
        var downloaded = 0
        if (current.autoDownload && downloadsAllowed) {
            val budget = if (current.retentionLastN > 0) current.retentionLastN else 5
            // Re-read so a localPath set by an earlier run is visible (skip-if-present).
            val byGuid = podcastDao.episodesForFeedAll(show.feedUrl).associateBy { it.guid }
            fetched.episodes.sortedByDescending { it.publishedAt }.take(budget).forEach { parsedEp ->
                val ep = byGuid[parsedEp.guid] ?: parsedEp
                val saved = downloader.downloadIfMissing(current, ep, globalTree)
                if (saved != null) {
                    podcastDao.setLocalPath(ep.guid, saved.uri, saved.bytes, System.currentTimeMillis())
                    downloaded++
                }
            }
        }
        return Outcome.Ok(fetched.episodes.size, freshEpisodes.size, downloaded)
    }

    companion object {
        /** Minimum gap between two on-visit checks of the same show. */
        const val VISIT_THROTTLE_MS = 15 * 60 * 1000L
    }
}

// ── Pure helpers (unit-tested: PodcastFeedRefresherTest) ─────────────────────

/** The freshly parsed show row with every user-owned field carried over from
 *  the stored row. A bare REPLACE of the parsed row reset displayOrder (and
 *  before earlier fixes the settings) on every periodic sync. */
fun mergeShowRefresh(existing: PodcastShowEntity, refreshed: PodcastShowEntity): PodcastShowEntity =
    refreshed.copy(
        subscribedAt = existing.subscribedAt,
        autoDownload = existing.autoDownload,
        retentionLastN = existing.retentionLastN,
        notifyOnNewEpisode = existing.notifyOnNewEpisode,
        downloadTreeUri = existing.downloadTreeUri,
        displayOrder = existing.displayOrder,
        // A feed that lost its artwork/title for one fetch must not blank the row.
        title = refreshed.title.takeIf { it.isNotBlank() && it != refreshed.feedUrl } ?: existing.title,
        artworkUrl = refreshed.artworkUrl ?: existing.artworkUrl,
        description = refreshed.description.ifBlank { existing.description }
    )

/** Parsed episodes with per-episode user state (played flag, offline copy)
 *  carried over from the stored rows keyed by guid; new guids insert as parsed. */
fun mergeEpisodeState(
    parsed: List<PodcastEpisodeEntity>,
    existingByGuid: Map<String, PodcastEpisodeEntity>
): List<PodcastEpisodeEntity> = parsed.map { e ->
    val existing = existingByGuid[e.guid] ?: return@map e
    e.copy(
        isPlayed = existing.isPlayed,
        localPath = existing.localPath,
        localBytes = existing.localBytes,
        downloadedAt = existing.downloadedAt
    )
}

/** Shows whose last successful check is older than [throttleMs] (never-checked
 *  rows count as stale), in list order. */
fun staleShows(
    shows: List<PodcastShowEntity>,
    nowMs: Long,
    throttleMs: Long = PodcastFeedRefresher.VISIT_THROTTLE_MS
): List<PodcastShowEntity> = shows.filter { nowMs - it.lastChecked >= throttleMs }

/** "Checked just now" / "Checked 12 min ago" / "Checked 3 h ago" / "Checked 2 d ago" /
 *  "Not checked yet" for the expanded-show header. */
fun formatCheckedAgo(lastCheckedMs: Long, nowMs: Long): String {
    if (lastCheckedMs <= 0L) return "Not checked yet"
    val ago = (nowMs - lastCheckedMs).coerceAtLeast(0L)
    val min = ago / 60_000L
    return when {
        min < 1L -> "Checked just now"
        min < 60L -> "Checked $min min ago"
        min < 24L * 60L -> "Checked ${min / 60L} h ago"
        else -> "Checked ${min / (24L * 60L)} d ago"
    }
}

/** Optimistic show row inserted the moment the user subscribes, so the list
 *  shows the podcast while its feed (often 1-2 MB, 1000+ episodes) downloads
 *  and parses. The real fetch replaces it by primary key; a failed fetch
 *  removes it. [title]/[artworkUrl] come from the directory hit when there is
 *  one, else the feed host. */
fun placeholderShow(
    feedUrl: String,
    title: String?,
    artworkUrl: String?,
    displayOrder: Int,
    nowMs: Long
): PodcastShowEntity {
    val host = runCatching { java.net.URI(feedUrl).host }.getOrNull()
    return PodcastShowEntity(
        feedUrl = feedUrl,
        title = title?.takeIf { it.isNotBlank() } ?: host?.takeIf { it.isNotBlank() } ?: feedUrl,
        artworkUrl = artworkUrl?.takeIf { it.isNotBlank() },
        description = "",
        lastChecked = 0L,
        subscribedAt = nowMs,
        displayOrder = displayOrder
    )
}
