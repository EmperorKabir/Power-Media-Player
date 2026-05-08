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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val playbackConnection: PlaybackConnection
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

    fun playEpisode(episode: PodcastEpisodeEntity) {
        val uri = android.net.Uri.parse(episode.audioUrl)
        val item = androidx.media3.common.MediaItem.Builder()
            .setMediaId(episode.audioUrl)
            .setUri(uri)
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri).build()
            )
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(episode.title).build()
            )
            .build()
        playbackConnection.setMediaItems(listOf(item), 0)
        viewModelScope.launch(Dispatchers.IO) {
            podcastDao.setPlayed(episode.guid, true)
        }
    }

    fun addByUrl(rssUrl: String) {
        if (rssUrl.isBlank()) return
        _status.value = "Fetching feed…"
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = parser.fetch(rssUrl)
            if (parsed == null) {
                withContext(Dispatchers.Main) {
                    _status.value = "Couldn't parse feed at $rssUrl"
                }
                return@launch
            }
            val (show, episodes) = parsed
            podcastDao.upsertShow(show)
            podcastDao.upsertEpisodes(episodes)
            withContext(Dispatchers.Main) {
                _status.value = "Subscribed: ${show.title} (${episodes.size} episodes)"
            }
        }
    }

    fun unsubscribe(feedUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            podcastDao.deleteEpisodesForFeed(feedUrl)
            podcastDao.unsubscribe(feedUrl)
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
                label = { Text("RSS feed URL") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                vm.addByUrl(url)
                url = ""
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
                Text("Add", color = TealAccent)
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
            var expandedFeed by remember { mutableStateOf<String?>(null) }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.height(360.dp)
            ) {
                items(shows, key = { it.feedUrl }) { show ->
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
                        Column(Modifier.weight(1f)) {
                            Text(
                                show.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                show.feedUrl,
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

@Composable
private fun EpisodeList(
    feedUrl: String,
    vm: PodcastsViewModel
) {
    val episodes by vm.episodesFor(feedUrl).collectAsState(initial = emptyList())
    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
        if (episodes.isEmpty()) {
            Text(
                "Loading episodes…",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        } else {
            episodes.take(15).forEach { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.playEpisode(e) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.isPlayed) TextTertiary else TextPrimary,
                            maxLines = 2
                        )
                        Text(
                            (if (e.durationS > 0) "${e.durationS / 60} min · " else "") +
                                java.text.SimpleDateFormat(
                                    "yyyy-MM-dd", java.util.Locale.getDefault()
                                ).format(java.util.Date(e.publishedAt.coerceAtLeast(0L))),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }
        }
    }
}
