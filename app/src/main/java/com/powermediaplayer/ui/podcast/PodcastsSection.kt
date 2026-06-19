package com.powermediaplayer.ui.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.podcast.RssFeedParser
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * §C10 — minimal podcast subscription manager: add by RSS URL, list
 * subscriptions, unsubscribe. Episode browser + auto-sync are the
 * next iteration. Schema, parser, and DAO are already wired so the
 * surface here is the user-visible entry.
 */
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val podcastDao: PodcastDao,
    private val playbackConnection: PlaybackConnection,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository
) : ViewModel() {
    private val parser = RssFeedParser()

    val shows: StateFlow<List<PodcastShowEntity>> =
        podcastDao.observeShows().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

    private val _status = kotlinx.coroutines.flow.MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun episodesFor(feedUrl: String): kotlinx.coroutines.flow.Flow<List<PodcastEpisodeEntity>> =
        podcastDao.observeEpisodes(feedUrl)

    /** Per-show episode totals + "new" counts, keyed by feedUrl (B1 show rows). */
    val feedCounts: StateFlow<Map<String, PodcastDao.FeedCounts>> =
        podcastDao.observeFeedCounts()
            .map { list -> list.associateBy { it.feedUrl } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Saved resume position for an episode's audio (B2 progress marker). */
    fun episodePosition(audioUrl: String): kotlinx.coroutines.flow.Flow<Long?> =
        lastPlayedRepo.observePositionFor(audioUrl)

    fun playEpisode(episode: PodcastEpisodeEntity) {
        val uri = android.net.Uri.parse(episode.audioUrl)
        viewModelScope.launch(Dispatchers.IO) {
            // Episode rows carry no artwork; resolve the show's image so the
            // player AND the Recents row both get a cover (fixes #3).
            val show = podcastDao.getShow(episode.feedUrl)
            val artUri = show?.artworkUrl
            val item = androidx.media3.common.MediaItem.Builder()
                .setMediaId(episode.audioUrl)
                .setUri(uri)
                .setRequestMetadata(
                    androidx.media3.common.MediaItem.RequestMetadata.Builder()
                        .setMediaUri(uri).build()
                )
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(show?.title ?: "")
                        .apply { if (!artUri.isNullOrBlank()) setArtworkUri(android.net.Uri.parse(artUri)) }
                        .build()
                )
                .build()
            // MediaController must be touched on the main thread.
            withContext(Dispatchers.Main) { playbackConnection.setMediaItems(listOf(item), 0) }
            // Record DIRECTLY into Recents (not the gated 5s-tick fallback) so a
            // mid-session switch becomes the most-recent row and cold-start resumes
            // IT (fixes #4/#5). source="LOCAL" + a remote URL is proven to resume.
            runCatching {
                lastPlayedRepo.recordPlay(
                    com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                        mediaUri = episode.audioUrl,
                        title = episode.title,
                        subtitle = show?.title ?: "Podcast",
                        artworkUri = artUri,
                        source = "LOCAL",
                        mediaKindOrdinal = 0,
                        lastPositionMs = 0L,
                        durationMs = episode.durationS * 1000L,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }
            // Mark "opened" (NOT completed — the episode-row marker derives
            // in-progress vs played from the saved resume position).
            podcastDao.setPlayed(episode.guid, true)
        }
    }

    private suspend fun setStatus(msg: String) =
        withContext(Dispatchers.Main) { _status.value = msg }

    fun addByUrl(rssUrl: String) {
        if (rssUrl.isBlank()) return
        _status.value = "Fetching feed…"
        viewModelScope.launch(Dispatchers.IO) {
            // An Apple Podcasts page (…/idNNNN) isn't an RSS feed — resolve its
            // real feedUrl via the iTunes lookup before trying to parse.
            val appleId = Regex("""/id(\d+)""").find(rssUrl)?.groupValues?.get(1)
            val target = if (rssUrl.contains("podcasts.apple.com") && appleId != null) {
                itunes.lookupFeedUrl(appleId) ?: rssUrl
            } else rssUrl
            when (val r = parser.fetchResult(target)) {
                is RssFeedParser.FetchResult.Ok -> {
                    podcastDao.upsertShow(r.show)
                    podcastDao.upsertEpisodes(r.episodes)
                    setStatus("Subscribed: ${r.show.title} (${r.episodes.size} episodes)")
                }
                is RssFeedParser.FetchResult.HttpError ->
                    setStatus("Feed returned HTTP ${r.code}. Try searching the show name instead.")
                is RssFeedParser.FetchResult.NotFeed ->
                    setStatus("That URL isn't an RSS feed (${r.reason}). Paste the RSS feed, or search by name.")
                RssFeedParser.FetchResult.Network ->
                    setStatus("Network error fetching the feed. Check your connection.")
            }
        }
    }

    fun unsubscribe(feedUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            podcastDao.deleteEpisodesForFeed(feedUrl)
            podcastDao.unsubscribe(feedUrl)
        }
    }

    /** §C10 LOCKED — iTunes search results. */
    private val itunes = com.powermediaplayer.podcast.ITunesPodcastSearch()
    private val _itunesResults =
        kotlinx.coroutines.flow.MutableStateFlow<List<com.powermediaplayer.podcast.ITunesPodcastSearch.Hit>>(emptyList())
    val itunesResults: StateFlow<List<com.powermediaplayer.podcast.ITunesPodcastSearch.Hit>> = _itunesResults

    fun searchItunes(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _itunesResults.value = itunes.search(query)
        }
    }

    fun subscribeFromItunes(hit: com.powermediaplayer.podcast.ITunesPodcastSearch.Hit) {
        addByUrl(hit.feedUrl)
    }

    fun setShowSettings(
        feedUrl: String,
        autoDownload: Boolean,
        retentionLastN: Int,
        notifyOnNewEpisode: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val show = podcastDao.getShow(feedUrl) ?: return@launch
            podcastDao.upsertShow(
                show.copy(
                    autoDownload = autoDownload,
                    retentionLastN = retentionLastN,
                    notifyOnNewEpisode = notifyOnNewEpisode
                )
            )
        }
    }
}

@Composable
fun PodcastsSection(
    vm: PodcastsViewModel = hiltViewModel()
) {
    val shows by vm.shows.collectAsState()
    val status by vm.status.collectAsState()
    var url by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Podcasts, contentDescription = null, tint = TealAccent)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Podcasts (${shows.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    "Subscribe by RSS URL. Episode list and auto-sync " +
                        "are wired and refresh on each visit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("RSS feed URL or search term") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    vm.addByUrl(url)
                    url = ""
                } else {
                    vm.searchItunes(url)
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
                Text("Add / search", color = TealAccent)
            }
        }
        // §C10 LOCKED — iTunes podcast search results inline. Tap a
        // row to subscribe.
        val hits by vm.itunesResults.collectAsState()
        if (hits.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Apple Podcasts results:", color = TextSecondary,
                style = MaterialTheme.typography.labelSmall)
            hits.take(8).forEach { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.subscribeFromItunes(hit); url = "" }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PodcastArtwork(hit.artworkUrl, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hit.title, color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(hit.author, color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Text("Subscribe", color = TealAccent,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Spacer(Modifier.height(8.dp))
        if (shows.isEmpty()) {
            Text(
                "No subscriptions yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )
        } else {
            val counts by vm.feedCounts.collectAsState()
            var expandedFeed by remember { mutableStateOf<String?>(null) }
            // Content-wrapping Column (NOT a fixed-height nested LazyColumn): the
            // host's outer scroll list provides scrolling, so the section sizes to
            // its content with no dead gap (fixes #6). The mini-player area is a
            // layout sibling above which content already reflows (AppNavigation
            // NonPlayerRoute), so no manual bottom inset is needed here.
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                shows.forEach { show ->
                    val c = counts[show.feedUrl]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedFeed = if (expandedFeed == show.feedUrl) null
                                else show.feedUrl
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodcastArtwork(show.artworkUrl, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                show.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 2
                            )
                            val total = c?.total ?: 0
                            val newCount = c?.unopened ?: 0
                            Text(
                                "$total episode${if (total == 1) "" else "s"}" +
                                    if (newCount > 0) " · $newCount new" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { vm.unsubscribe(show.feedUrl) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Unsubscribe",
                                tint = ErrorRed
                            )
                        }
                    }
                    if (expandedFeed == show.feedUrl) {
                        ShowSettingsRow(show = show, vm = vm)
                        EpisodeList(
                            feedUrl = show.feedUrl,
                            vm = vm
                        )
                    }
                }
            }
        }
    }
}

/** §C10 per-show settings row — autoDownload + retention + notify. */
@Composable
private fun ShowSettingsRow(
    show: PodcastShowEntity,
    vm: PodcastsViewModel
) {
    var autoDl by remember(show.feedUrl) { mutableStateOf(show.autoDownload) }
    var retention by remember(show.feedUrl) { mutableStateOf(show.retentionLastN) }
    var notify by remember(show.feedUrl) { mutableStateOf(show.notifyOnNewEpisode) }
    fun persist() = vm.setShowSettings(show.feedUrl, autoDl, retention, notify)

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(
                checked = autoDl,
                onCheckedChange = { autoDl = it; persist() }
            )
            Text(
                "  Auto-download new episodes",
                color = if (autoDl) TextPrimary else TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(
                checked = notify,
                onCheckedChange = { notify = it; persist() }
            )
            Text(
                "  Notify on new episode",
                color = if (notify) TextPrimary else TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Keep last (0 = all):",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.OutlinedTextField(
                value = retention.toString(),
                onValueChange = {
                    retention = it.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    persist()
                },
                singleLine = true,
                modifier = Modifier.width(96.dp)
            )
        }
    }
}

@Composable
private fun EpisodeList(
    feedUrl: String,
    vm: PodcastsViewModel
) {
    val episodes by vm.episodesFor(feedUrl).collectAsState(initial = emptyList())
    Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
        if (episodes.isEmpty()) {
            Text(
                "Loading episodes…",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        } else {
            episodes.take(15).forEach { e -> EpisodeRow(e, vm) }
        }
    }
}

/** One episode row. The listened marker is DERIVED from the saved resume
 *  position (history row keyed by audioUrl), not the open-on-tap flag:
 *  New (accent dot) / In-progress (bar + "min left") / Played (check). */
@Composable
private fun EpisodeRow(e: PodcastEpisodeEntity, vm: PodcastsViewModel) {
    val posMs by vm.episodePosition(e.audioUrl).collectAsState(initial = null)
    val durMs = e.durationS * 1000L
    val progress = if (durMs > 0 && posMs != null)
        (posMs!!.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val played = progress >= 0.95f
    val inProgress = (posMs ?: 0L) > 0L && !played
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.playEpisode(e) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                played -> Icon(
                    Icons.Filled.Check, "Played",
                    tint = TextTertiary, modifier = Modifier.size(18.dp)
                )
                inProgress -> {}
                else -> Icon(
                    Icons.Filled.FiberManualRecord, "New",
                    tint = TealAccent, modifier = Modifier.size(9.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (played) TextTertiary else TextPrimary,
                maxLines = 2
            )
            val date = java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.getDefault()
            ).format(java.util.Date(e.publishedAt.coerceAtLeast(0L)))
            val dur = if (e.durationS > 0) "${e.durationS / 60} min" else ""
            val left = if (inProgress && durMs > 0)
                " · ${((durMs - (posMs ?: 0L)) / 60000L).coerceAtLeast(0L)} min left" else ""
            Text(
                listOf(dur, date).filter { it.isNotBlank() }.joinToString(" · ") + left,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            if (inProgress) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = TealAccent,
                    trackColor = TealAccent.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
    }
}

/** Rounded show/episode thumbnail via Coil (the app's network image loader is
 *  already configured); falls back to a podcast glyph when there's no artwork. */
@Composable
private fun PodcastArtwork(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated)
    ) {
        if (!url.isNullOrBlank()) {
            coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(LocalContext.current)
                    .data(url).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Filled.Podcasts,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.align(Alignment.Center).size(size * 0.5f)
            )
        }
    }
}
