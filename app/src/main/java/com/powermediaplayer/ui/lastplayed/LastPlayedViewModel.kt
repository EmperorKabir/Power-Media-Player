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
    private val playbackConnection: PlaybackConnection,
    private val spotifyProvider: com.powermediaplayer.cloud.SpotifyProvider,
    private val bookmarkDao: com.powermediaplayer.data.db.dao.BookmarkDao
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
     * Bookmarks for a single Last Played row. Used by the expandable
     * dropdown so each row in Recent / Pinned can surface up to 5 (or
     * unlimited, for pinned) timestamped seek-points without leaving
     * the Last Played tab.
     */
    fun bookmarksFor(mediaUri: String):
        kotlinx.coroutines.flow.Flow<List<com.powermediaplayer.data.db.entity.BookmarkEntity>> =
        bookmarkDao.observeForMedia(mediaUri)

    fun deleteBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.delete(id) }
    }

    /**
     * Hand off play of a row's media. Local + Drive (SAF content://)
     * sources resolve to a single MediaItem fed to PlaybackConnection
     * at [atPositionMs] if provided, else the row's last-known
     * position. Spotify rows route through SpotifyProvider so they
     * resume on the user's Connect device.
     *
     * @param atPositionMs override seek position — used by the
     *   bookmark dropdown so tapping a bookmark in Last Played jumps
     *   straight to that timestamp instead of the file's last-stored
     *   position.
     */
    fun playLocalAt(
        item: LastPlayedRepository.HistoryItem,
        atPositionMs: Long? = null
    ) {
        // Spotify rows: route via Spotify Connect, including the bookmark
        // override position. The mirror polling handles the play-state
        // update so the Player tab swaps over.
        if (item.source == LastPlayedRepository.Source.SPOTIFY) {
            val targetPos = atPositionMs ?: item.lastPositionMs
            viewModelScope.launch(Dispatchers.IO) {
                val play = runCatching {
                    spotifyProvider.playTrackOnConnectDevice(item.mediaUri, contextUri = null)
                }
                if (play.getOrNull()?.isSuccess == true && targetPos > 0L) {
                    // /seek lands a moment after /play; small dwell so
                    // Spotify Connect has finished loading the track.
                    kotlinx.coroutines.delay(500)
                    runCatching { spotifyProvider.seekTo(targetPos) }
                    spotifyProvider.startPlaybackPolling()
                }
            }
            return
        }

        // Local + Drive (SAF) — both fed via the local ExoPlayer.
        // Mirror cleanup matches LibraryViewModel.stopSpotifyMirrorIfActive
        // so the Player tab doesn't keep displaying Spotify metadata
        // while local audio plays underneath.
        if (spotifyProvider.spotifyState.value != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { spotifyProvider.pause() }
            }
            spotifyProvider.stopPlaybackPolling()
        }
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
        playbackConnection.seekTo(atPositionMs ?: item.lastPositionMs)
    }
}
