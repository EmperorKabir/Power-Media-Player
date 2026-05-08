package com.powermediaplayer.ui.smartplaylists

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.db.dao.SmartPlaylistDao
import com.powermediaplayer.data.db.entity.SmartPlaylistEntity
import com.powermediaplayer.playlist.SmartPlaylistResolver
import com.powermediaplayer.ui.library.LibraryViewModel
import com.powermediaplayer.ui.library.MediaFileInfo
import com.powermediaplayer.ui.theme.TealAccent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §C6 — horizontal chip rail above the Library list. Each chip is a
 * saved smart playlist; tap = resolve via [SmartPlaylistResolver]
 * against the current Library + history snapshot, then play.
 */
@HiltViewModel
class SmartPlaylistRailViewModel @Inject constructor(
    private val dao: SmartPlaylistDao,
    private val historyDao: com.powermediaplayer.data.db.dao.PlaybackHistoryDao,
    private val favoriteDao: com.powermediaplayer.data.db.dao.FavoriteDao,
    private val bookmarkDao: com.powermediaplayer.data.db.dao.BookmarkDao
) : ViewModel() {
    val playlists: StateFlow<List<SmartPlaylistEntity>> =
        dao.getAll().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

    suspend fun resolve(
        playlist: SmartPlaylistEntity,
        files: List<MediaFileInfo>
    ): List<MediaFileInfo> = withContext(Dispatchers.IO) {
        val history = historyDao.snapshot()
        val playCount = history.groupingBy { it.mediaUri }.eachCount()
        val lastPlayed = history.associate { it.mediaUri to it.lastPlayedAt }
        val bookmarks = bookmarkDao.observeAll().first()
            .map { it.mediaUri }.toSet()
        val favourites = favoriteDao.getAll().map { it.uri }.toSet()
        SmartPlaylistResolver.resolve(
            files = files,
            rulesJson = playlist.rulesJson,
            history = SmartPlaylistResolver.HistorySnapshot(
                playCount = playCount,
                lastPlayedAt = lastPlayed,
                bookmarkUris = bookmarks,
                favouriteUris = favourites
            )
        )
    }
}

@Composable
fun SmartPlaylistRail(
    onPlayResolved: (List<MediaFileInfo>) -> Unit,
    libraryVm: LibraryViewModel = hiltViewModel(),
    railVm: SmartPlaylistRailViewModel = hiltViewModel()
) {
    val playlists by railVm.playlists.collectAsState()
    val libraryState by libraryVm.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    if (playlists.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        playlists.forEach { p ->
            AssistChip(
                onClick = {
                    scope.launch {
                        val all = libraryState.audioFiles + libraryState.videoFiles
                        val resolved = railVm.resolve(p, all)
                        onPlayResolved(resolved)
                    }
                },
                label = { Text(p.name) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = TealAccent.copy(alpha = 0.15f),
                    labelColor = TealAccent
                )
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}
