package com.powermediaplayer.alarm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.db.dao.BookmarkDao
import com.powermediaplayer.data.db.dao.FavoriteDao
import com.powermediaplayer.data.db.dao.PlaybackHistoryDao
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * §10.1 — typeahead picker over bookmarks / favourites / last-played
 * / local library / cached cloud index. Replaces the raw URI text
 * field on the alarm editor. Returns (uri, displayLabel).
 */
data class AlarmPickerEntry(
    val uri: String,
    val title: String,
    val subtitle: String,
    val source: String,
    val icon: ImageVector
)

@HiltViewModel
class AlarmMediaPickerViewModel @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: PlaybackHistoryDao,
    private val smartPlaylistDao: com.powermediaplayer.data.db.dao.SmartPlaylistDao
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    private val bookmarks: Flow<List<AlarmPickerEntry>> =
        bookmarkDao.observeAll().map { list ->
            list.map { b ->
                AlarmPickerEntry(
                    uri = b.mediaUri,
                    title = b.label.ifBlank { "Bookmark" },
                    subtitle = b.mediaUri.substringAfterLast('/'),
                    source = "Bookmarks",
                    icon = Icons.Filled.Bookmark
                )
            }
        }

    private val favourites: Flow<List<AlarmPickerEntry>> =
        favoriteDao.observeAllUris().map { uris ->
            uris.map { u ->
                AlarmPickerEntry(
                    uri = u,
                    title = u.substringAfterLast('/').ifBlank { u },
                    subtitle = "Favourite",
                    source = "Favourites",
                    icon = Icons.Filled.Star
                )
            }
        }

    private val recents: Flow<List<AlarmPickerEntry>> =
        historyDao.recent(25).map { rows ->
            rows.map { r ->
                AlarmPickerEntry(
                    uri = r.mediaUri,
                    title = r.title.ifBlank { r.mediaUri.substringAfterLast('/') },
                    subtitle = r.subtitle,
                    source = if (r.source == "SPOTIFY") "Cloud" else "Last Played",
                    icon = if (r.source == "SPOTIFY") Icons.Filled.Cloud
                    else Icons.Filled.History
                )
            }
        }

    private val playlists: Flow<List<AlarmPickerEntry>> =
        smartPlaylistDao.getAll().map { rows ->
            rows.map { p ->
                // §C10 LOCKED alarm source — smart playlists carry the
                // "smartplaylist://" pseudo-scheme so AlarmReceiver
                // resolves them via SmartPlaylistResolver at fire time.
                AlarmPickerEntry(
                    uri = "smartplaylist://${p.id}",
                    title = p.name,
                    subtitle = "Smart playlist",
                    source = "Playlists",
                    icon = Icons.Filled.Star
                )
            }
        }

    val all: StateFlow<List<AlarmPickerEntry>> = combine(
        bookmarks, favourites, recents, playlists, _query
    ) { entries: Array<Any> ->
        @Suppress("UNCHECKED_CAST")
        val b = entries[0] as List<AlarmPickerEntry>
        @Suppress("UNCHECKED_CAST")
        val f = entries[1] as List<AlarmPickerEntry>
        @Suppress("UNCHECKED_CAST")
        val r = entries[2] as List<AlarmPickerEntry>
        @Suppress("UNCHECKED_CAST")
        val pl = entries[3] as List<AlarmPickerEntry>
        val q = entries[4] as String
        val merged = (pl + b + f + r).distinctBy { it.uri }
        if (q.isBlank()) merged
        else merged.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.subtitle.contains(q, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmMediaPickerSheet(
    onPicked: (uri: String, label: String) -> Unit,
    onDismiss: () -> Unit,
    vm: AlarmMediaPickerViewModel = hiltViewModel()
) {
    val all by vm.all.collectAsState()
    val q by vm.query.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // heightIn (audit 6.8): a fixed 540dp exceeded landscape-
            // phone window heights, clipping the sheet's own controls.
            .heightIn(max = 540.dp)
            .fillMaxHeight(0.8f)) {
            Text(
                "Pick alarm sound",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent
            )
            Text(
                "Searches your bookmarks, favourites, and recent plays.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = q,
                onValueChange = { vm.setQuery(it) },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            if (all.isEmpty()) {
                Text(
                    if (q.isBlank()) "Nothing yet — bookmark or favourite a track first."
                    else "No matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(all, key = { it.uri + "|" + it.source }) { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(e.uri, e.title) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(e.icon, contentDescription = null, tint = TealAccent)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    "${e.source} • ${e.subtitle}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
