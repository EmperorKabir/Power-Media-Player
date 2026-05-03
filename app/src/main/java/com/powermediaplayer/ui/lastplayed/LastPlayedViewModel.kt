package com.powermediaplayer.ui.lastplayed

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.repository.LastPlayedRepository
import com.powermediaplayer.service.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backing VM for the Last Played tab. Wraps the repository's
 * dynamic + pinned flows and exposes side-effect entry points.
 *
 * Note: actual playback dispatch into Library / Cloud / Spotify
 * happens at the Screen level via the existing tab ViewModels —
 * this VM only resolves the right action and triggers it.
 */
@HiltViewModel
class LastPlayedViewModel @Inject constructor(
    private val repo: LastPlayedRepository,
    private val playbackConnection: PlaybackConnection
) : ViewModel() {

    val dynamic: StateFlow<List<LastPlayedRepository.HistoryItem>> =
        repo.observeDynamic().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val pinned: StateFlow<List<LastPlayedRepository.HistoryItem>> =
        repo.observePinned().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Returns true if pinned; false (with reason snackbar) if 10/10. */
    suspend fun pin(uri: String): Boolean = repo.pin(uri).isSuccess

    fun unpin(uri: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.unpin(uri) }
    }

    fun reorderPinned(uri: String, newOrder: Int) {
        viewModelScope.launch(Dispatchers.IO) { repo.reorderPinned(uri, newOrder) }
    }

    fun delete(uri: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.delete(uri) }
    }

    /**
     * Hand off play of a row's media. Local sources resolve to a
     * single MediaItem fed to PlaybackConnection at the saved
     * position. Drive / Spotify rows are routed back through their
     * provider VMs by the Screen layer (this VM only handles LOCAL).
     */
    fun playLocalAt(item: LastPlayedRepository.HistoryItem) {
        val uri = runCatching { Uri.parse(item.mediaUri) }.getOrNull() ?: return
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId(item.mediaUri)
            .setUri(uri)
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri).build()
            )
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subtitle)
                    .build()
            )
            .build()
        playbackConnection.setMediaItems(listOf(mediaItem), 0)
        playbackConnection.seekTo(item.lastPositionMs)
    }
}
