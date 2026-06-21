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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.flow.first
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val podcastDao: PodcastDao,
    private val playbackConnection: PlaybackConnection,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    private val settings: com.powermediaplayer.data.preferences.SettingsDataStore
) : ViewModel() {
    private val parser = RssFeedParser()
    private val downloader = com.powermediaplayer.podcast.PodcastDownloader(appContext)

    /** §C10 — guids with an in-flight manual download (drives the row spinner). */
    private val _downloading = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading
    private fun markDownloading(guid: String, on: Boolean) {
        _downloading.value = if (on) _downloading.value + guid else _downloading.value - guid
    }

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
        viewModelScope.launch(Dispatchers.IO) {
            // Episode rows carry no artwork; resolve the show's image so the
            // player AND the Recents row both get a cover (fixes #3).
            val show = podcastDao.getShow(episode.feedUrl)
            val artUri = show?.artworkUrl
            // §C10 — play the downloaded file when present + readable; the
            // playback source (localConfiguration.uri set via setUri) overrides
            // the stream in the service's resolver. Keep audioUrl as mediaId +
            // requestMetadata.mediaUri so the resume position + Recents row stay
            // keyed to the episode, not the on-disk path (so download/delete
            // never loses progress and the Recents dedup is stable).
            val keyUri = android.net.Uri.parse(episode.audioUrl)
            val playUri = resolvePlayableLocal(episode)
                ?.let { android.net.Uri.parse(it) } ?: keyUri
            val item = androidx.media3.common.MediaItem.Builder()
                .setMediaId(episode.audioUrl)
                .setUri(playUri)
                .setRequestMetadata(
                    androidx.media3.common.MediaItem.RequestMetadata.Builder()
                        .setMediaUri(keyUri).build()
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

    /**
     * §C10 — returns the downloaded copy's uri string if the episode has one
     * AND it is still readable; otherwise null. A stale localPath (file removed
     * out from under us, or a revoked SAF grant) self-heals: the row is cleared
     * so the "downloaded" badge disappears and playback falls back to the stream.
     */
    private suspend fun resolvePlayableLocal(episode: PodcastEpisodeEntity): String? {
        val path = episode.localPath?.takeIf { it.isNotBlank() } ?: return null
        val readable = runCatching {
            val u = android.net.Uri.parse(path)
            when (u.scheme) {
                "content" ->
                    appContext.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
                "file", null ->
                    java.io.File(u.path ?: path).let { it.exists() && it.canRead() }
                else -> java.io.File(path).exists()
            }
        }.getOrDefault(false)
        if (!readable) {
            runCatching { podcastDao.clearLocalPath(episode.guid) }
            return null
        }
        return path
    }

    // ── §C10 downloads — manual per-episode + per-show management ──────────

    fun downloadEpisode(e: PodcastEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_downloading.value.contains(e.guid)) return@launch
            markDownloading(e.guid, true)
            try {
                val show = podcastDao.getShow(e.feedUrl) ?: return@launch
                val global = settings.podcastDownloadTreeUri.first().ifBlank { null }
                val saved = downloader.download(show, e, global)
                if (saved != null) {
                    podcastDao.setLocalPath(e.guid, saved.uri, saved.bytes, System.currentTimeMillis())
                    setStatus("Downloaded: ${e.title}")
                } else {
                    setStatus("Download failed: ${e.title}")
                }
            } finally {
                markDownloading(e.guid, false)
            }
        }
    }

    fun deleteEpisodeDownload(e: PodcastEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            e.localPath?.takeIf { it.isNotBlank() }?.let {
                com.powermediaplayer.util.SafStorage.delete(appContext, android.net.Uri.parse(it))
            }
            podcastDao.clearLocalPath(e.guid)
            setStatus("Deleted download: ${e.title}")
        }
    }

    fun downloadLatestForShow(feedUrl: String, n: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val show = podcastDao.getShow(feedUrl) ?: return@launch
            val global = settings.podcastDownloadTreeUri.first().ifBlank { null }
            val budget = if (n > 0) n else 5
            val episodes = podcastDao.observeEpisodes(feedUrl).first()
                .sortedByDescending { it.publishedAt }.take(budget)
            var ok = 0
            episodes.forEach { e ->
                markDownloading(e.guid, true)
                try {
                    val saved = downloader.downloadIfMissing(show, e, global)
                    if (saved != null) {
                        podcastDao.setLocalPath(
                            e.guid, saved.uri, saved.bytes, System.currentTimeMillis()
                        )
                        ok++
                    }
                } finally {
                    markDownloading(e.guid, false)
                }
            }
            setStatus("Downloaded $ok new episode(s) for ${show.title}")
        }
    }

    fun deleteAllForShow(feedUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = podcastDao.downloadedForFeed(feedUrl)
            downloaded.forEach { e ->
                e.localPath?.let {
                    com.powermediaplayer.util.SafStorage.delete(appContext, android.net.Uri.parse(it))
                }
                podcastDao.clearLocalPath(e.guid)
            }
            setStatus("Deleted ${downloaded.size} download(s)")
        }
    }

    /** §C10/Part 4.2 — set (or clear) this show's download folder. */
    fun setShowFolder(feedUrl: String, treeUri: android.net.Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            val show = podcastDao.getShow(feedUrl) ?: return@launch
            val uriStr = treeUri?.let {
                com.powermediaplayer.util.SafStorage.persistTree(appContext, it)
                it.toString()
            }
            podcastDao.upsertShow(show.copy(downloadTreeUri = uriStr))
            setStatus(if (uriStr == null) "Folder reset to default" else "Download folder updated")
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
                    podcastDao.syncEpisodes(r.episodes)
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
        // §C10/Part 4.2 — per-show download folder picker (SAF write grant).
        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri -> if (uri != null) vm.setShowFolder(show.feedUrl, uri) }
        val folderLabel = remember(show.downloadTreeUri) {
            show.downloadTreeUri
                ?.let { com.powermediaplayer.util.SafStorage.treeLabel(android.net.Uri.parse(it)) }
                ?: "Default (app storage)"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Folder, contentDescription = null,
                tint = TextSecondary, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Folder: $folderLabel",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { folderPicker.launch(null) }) {
                Text("Change", color = TealAccent, style = MaterialTheme.typography.labelSmall)
            }
            if (show.downloadTreeUri != null) {
                TextButton(onClick = { vm.setShowFolder(show.feedUrl, null) }) {
                    Text("Reset", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // §C10 — bulk download / delete for the whole show.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.downloadLatestForShow(show.feedUrl, retention) }) {
                Icon(
                    Icons.Filled.Download, contentDescription = null,
                    tint = TealAccent, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Download latest ${if (retention > 0) retention else 5}",
                    color = TealAccent, style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.deleteAllForShow(show.feedUrl) }) {
                Icon(
                    Icons.Filled.DeleteOutline, contentDescription = null,
                    tint = ErrorRed, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Delete downloads", color = ErrorRed,
                    style = MaterialTheme.typography.labelSmall
                )
            }
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
    val pos = posMs ?: 0L
    // A feed's declared itunes:duration is sometimes wrong (seen: 4 min for a
    // 55 min episode). If the saved position is past it, the declared duration
    // is bogus → treat as finished and don't show a misleading total/bar.
    val durSane = durMs > 0L && pos <= durMs + durMs / 20
    val played = (durSane && pos >= durMs * 95 / 100) || (durMs in 1 until pos)
    val inProgress = pos > 0L && !played
    val progress = if (durSane && inProgress)
        (pos.toFloat() / durMs).coerceIn(0f, 1f) else 0f
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
            // Clear: total duration when unplayed, "X min left" while in
            // progress, "Played" when finished (incl. the bogus-duration case).
            val timeInfo = when {
                played -> "Played"
                inProgress && durSane ->
                    "${((durMs - pos) / 60000L).coerceAtLeast(0L)} min left"
                durMs > 0L -> "${durMs / 60000L} min"
                else -> ""
            }
            Text(
                listOf(timeInfo, date).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            if (inProgress && durSane) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = TealAccent,
                    trackColor = TealAccent.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
        // §C10 — per-episode download / downloaded-badge+delete affordance.
        val downloadingSet by vm.downloading.collectAsState()
        val isDownloading = downloadingSet.contains(e.guid)
        val isDownloaded = !e.localPath.isNullOrBlank()
        var confirmDelete by remember(e.guid) { mutableStateOf(false) }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                isDownloading -> CircularProgressIndicator(
                    color = TealAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
                isDownloaded -> IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Filled.DownloadDone,
                        contentDescription = "Downloaded — tap to delete",
                        tint = TealAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> IconButton(onClick = { vm.downloadEpisode(e) }) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Download episode",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete download?") },
                text = { Text("Remove the downloaded file for \"${e.title}\". Your listening progress is kept.") },
                confirmButton = {
                    TextButton(onClick = { vm.deleteEpisodeDownload(e); confirmDelete = false }) {
                        Text("Delete", color = ErrorRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
            )
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
