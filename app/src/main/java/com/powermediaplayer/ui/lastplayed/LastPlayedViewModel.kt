package com.powermediaplayer.ui.lastplayed

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.repository.LastPlayedRepository
import com.powermediaplayer.service.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backing VM for the Last Played tab. Wraps the repository's
 * dynamic + pinned flows and exposes side-effect entry points.
 *
 * Recents and Pinned are now distinct id-spaces:
 *   - Recents uses a [PlaybackHistoryEntity] id (one per play session)
 *   - Pinned uses a [HistoryFavouriteEntity] id (one per pin snapshot)
 * Bookmark dropdowns query different tables for each.
 */
@HiltViewModel
class LastPlayedViewModel @Inject constructor(
    private val repo: LastPlayedRepository,
    private val playbackConnection: PlaybackConnection,
    private val spotifyProvider: com.powermediaplayer.cloud.SpotifyProvider,
    val mediaOverrideDao: com.powermediaplayer.data.db.dao.MediaOverrideDao,
    @param:dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context
) : ViewModel() {

    /**
     * Transient user-visible messages emitted by failure paths in this
     * VM (e.g. Spotify Connect has no active device, token expired).
     * Collected by [LastPlayedScreen] and surfaced via SnackbarHost.
     * `extraBufferCapacity = 4` so a rapid burst (token-expired + no-
     * device + manual replay) doesn't drop the first emission.
     */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * §C7 — drop overrides when a row stops being pinned.
     */
    fun clearOverridesForUri(uri: String) {
        viewModelScope.launch(Dispatchers.IO) { mediaOverrideDao.clear(uri) }
    }

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

    /**
     * Pinned albums — sibling section to pinned tracks. Shares the same
     * 10-slot cap (enforced at the repository layer). Tap an album row
     * to expand member tracks; tap a track to play that one.
     */
    val pinnedAlbums: StateFlow<List<LastPlayedRepository.PinnedAlbumItem>> =
        repo.observePinnedAlbums().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun observePinnedAlbumTracks(albumId: Long) = repo.observeAlbumTracks(albumId)

    suspend fun pinAlbum(
        albumKey: String,
        title: String,
        artist: String,
        artworkUri: String?,
        tracks: List<LastPlayedRepository.AlbumTrackToPin>
    ): Boolean = repo.pinAlbum(albumKey, title, artist, artworkUri, tracks).isSuccess

    fun unpinAlbum(albumId: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo.unpinAlbum(albumId) }
    }

    /** Tap a pinned-album track → play that single file. Reuses the
     *  same MediaItem build path as playLocalAt for chapters parsing. */
    fun playAlbumTrack(trackUri: String, title: String) {
        val uri = runCatching { Uri.parse(trackUri) }.getOrNull() ?: return
        viewModelScope.launch {
            val mediaItem = withContext(Dispatchers.IO) {
                val chapterExtras = runCatching {
                    com.powermediaplayer.util.M4bChapterParser
                        .extractChaptersAsBundle(context, uri)
                }.getOrDefault(android.os.Bundle())
                androidx.media3.common.MediaItem.Builder()
                    .setMediaId(trackUri)
                    .setUri(uri)
                    .setRequestMetadata(
                        androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setMediaUri(uri).build()
                    )
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(title)
                            .setExtras(chapterExtras)
                            .build()
                    )
                    .build()
            }
            playbackConnection.setMediaItems(listOf(mediaItem), 0)
        }
    }

    /** Pin a Recents row by its session id. False on full (10/10). */
    suspend fun pinSession(historyId: Long): Boolean = repo.pinSession(historyId).isSuccess

    fun unpin(favouriteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            // §C7 — auto-clear per-file overrides when unpinning. Look
            // up the row's mediaUri before delete so the override row
            // can be cleared too.
            val mediaUri = repo.snapshotFavourites()
                .firstOrNull { it.id == favouriteId }?.mediaUri
            repo.unpin(favouriteId)
            if (!mediaUri.isNullOrBlank()) mediaOverrideDao.clear(mediaUri)
        }
    }

    fun reorderPinned(favouriteId: Long, newOrder: Int) {
        viewModelScope.launch(Dispatchers.IO) { repo.reorderPinned(favouriteId, newOrder) }
    }

    fun deleteRecentsRow(historyId: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo.delete(historyId) }
    }

    fun clearAllRecents() {
        viewModelScope.launch(Dispatchers.IO) { repo.clearAllRecents() }
    }

    /**
     * UI-facing bookmark shape so the Last Played screen does not have
     * to switch on entity type. Both [HistoryBookmarkEntity] (Recents)
     * and [FavouriteBookmarkEntity] (Pinned) collapse to this.
     */
    data class BookmarkRow(val id: Long, val positionMs: Long, val label: String)

    /**
     * Recents bookmarks for a single session row. Editable from the
     * Last Played UI; deleting one here does NOT touch the Player
     * tab's bookmarks table.
     */
    fun recentsBookmarksFor(historyId: Long): Flow<List<BookmarkRow>> =
        repo.observeSessionBookmarks(historyId).map { list ->
            list.map { BookmarkRow(it.id, it.positionMs, it.label) }
        }

    fun deleteRecentsBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteSessionBookmark(id) }
    }

    /**
     * Pinned bookmarks for a single pinned row. Snapshotted at pin
     * time; editable only from the Pinned section UI.
     */
    fun pinnedBookmarksFor(favouriteId: Long): Flow<List<BookmarkRow>> =
        repo.observeFavouriteBookmarks(favouriteId).map { list ->
            list.map { BookmarkRow(it.id, it.positionMs, it.label) }
        }

    fun deletePinnedBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteFavouriteBookmark(id) }
    }

    /**
     * Hand off play of a row's media. Local + Drive (SAF content://)
     * sources resolve to a single MediaItem fed to PlaybackConnection
     * at [atPositionMs] if provided, else the row's last-known
     * position. Spotify rows route through SpotifyProvider so they
     * resume on the user's Connect device.
     *
     * Each tap creates a NEW play session row in playback_history so
     * the Recents list reflects the user's actual sequence (A → B → A
     * produces three rows).
     */
    fun playLocalAt(
        item: LastPlayedRepository.HistoryItem,
        atPositionMs: Long? = null
    ) {
        // Spotify rows: route via Spotify Connect, including the
        // bookmark override position. Polling starts unconditionally on
        // play success (the previous gating-on-positive-position bug
        // left the Player tab showing stale local metadata when a
        // Spotify entry was tapped from a fresh-position row).
        if (item.source == LastPlayedRepository.Source.SPOTIFY) {
            val targetPos = atPositionMs ?: item.lastPositionMs
            // Stop the local ExoPlayer first — otherwise tapping a
            // Spotify row from Last Played leaves any currently-playing
            // local audio audible behind the Spotify Connect track.
            runCatching { playbackConnection.pause() }
            viewModelScope.launch(Dispatchers.IO) {
                val play = runCatching {
                    spotifyProvider.playTrackOnConnectDevice(item.mediaUri, contextUri = null)
                }.getOrNull()
                val ok = play?.isSuccess == true
                if (ok) {
                    // Always start polling so the Player tab swaps to
                    // the Spotify mirror — independent of whether the
                    // user is jumping to a saved position.
                    spotifyProvider.startPlaybackPolling()
                    if (targetPos > 0L) {
                        // /seek lands a moment after /play; small dwell
                        // so Spotify Connect has finished loading.
                        kotlinx.coroutines.delay(500)
                        runCatching { spotifyProvider.seekTo(targetPos) }
                    }
                    // Record a NEW Spotify session so the Recents list
                    // shows the replay as its own row.
                    runCatching {
                        repo.recordPlay(
                            com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                                mediaUri = item.mediaUri,
                                title = item.title,
                                subtitle = item.subtitle,
                                artworkUri = item.artworkUri,
                                source = "SPOTIFY",
                                mediaKindOrdinal = item.mediaKindOrdinal,
                                lastPositionMs = targetPos,
                                durationMs = item.durationMs,
                                lastPlayedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } else {
                    // Surface the failure. Earlier this used a SharedFlow
                    // routed to LastPlayedScreen's SnackbarHost, but the
                    // caller has ALREADY navigated to the Player screen
                    // by the time this branch fires (onNavigateToPlayer
                    // runs synchronously before the IO coroutine
                    // resolves). The snackbar host of the now-gone
                    // LastPlayed screen never receives the message →
                    // user sees nothing. Toast is system-level and
                    // survives navigation; perfect for this signal.
                    val msg = play?.exceptionOrNull()?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: "No active Spotify device — open Spotify on your phone or a speaker, then tap again"
                    com.powermediaplayer.diag.DiagLog.event(
                        "SPOTIFY",
                        "tap-fail toast → $msg"
                    )
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context, msg, android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    // Keep emitting on _messages too so a future
                    // app-wide snackbar can also catch it.
                    _messages.emit(msg)
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
        val targetPos = atPositionMs ?: item.lastPositionMs
        // M4bChapterParser opens MediaExtractor + walks the full MP4 box
        // hierarchy synchronously. For multi-GB Drive-backed audiobooks
        // accessed via content:// SAF this routinely blocked the Main
        // thread > 5 s → ANR. Mirror LibraryViewModel.playSingle: build
        // the MediaItem on Dispatchers.IO, then hop back to Main for
        // the MediaController calls.
        viewModelScope.launch {
            val mediaItem = withContext(Dispatchers.IO) {
                val chapterExtras = runCatching {
                    com.powermediaplayer.util.M4bChapterParser
                        .extractChaptersAsBundle(context, uri)
                }.getOrDefault(android.os.Bundle())
                androidx.media3.common.MediaItem.Builder()
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
                            .setExtras(chapterExtras)
                            .build()
                    )
                    .build()
            }
            playbackConnection.setMediaItems(listOf(mediaItem), 0)
            playbackConnection.seekTo(targetPos)
        }
        // Record a NEW session so the Recents list reflects this play
        // as its own row (the user-visible "A → B → A produces three
        // rows" contract).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.recordPlay(
                    com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                        mediaUri = item.mediaUri,
                        title = item.title,
                        subtitle = item.subtitle,
                        artworkUri = item.artworkUri,
                        source = when (item.source) {
                            LastPlayedRepository.Source.LOCAL -> "LOCAL"
                            LastPlayedRepository.Source.DRIVE -> "DRIVE"
                            else -> "LOCAL"
                        },
                        mediaKindOrdinal = item.mediaKindOrdinal,
                        lastPositionMs = targetPos,
                        durationMs = item.durationMs,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
