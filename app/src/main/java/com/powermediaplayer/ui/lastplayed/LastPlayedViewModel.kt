package com.powermediaplayer.ui.lastplayed

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.repository.LastPlayedRepository
import com.powermediaplayer.service.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    private val settingsDataStore: com.powermediaplayer.data.preferences.SettingsDataStore,
    private val driveTagEnricher: com.powermediaplayer.cloud.DriveTagEnricher,
    private val podcastOfflineResolver: com.powermediaplayer.podcast.PodcastOfflineResolver,
    private val driveOfflineResolver: com.powermediaplayer.cloud.DriveOfflineResolver,
    private val offlineMediaManager: com.powermediaplayer.offline.OfflineMediaManager,
    @param:dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context
) : ViewModel() {

    // ── Offline download / delete from the 3-dot menu ─────────────────────────
    /** Single source of truth for "which Last Played rows are offline": Drive ids
     *  (SAF content:// AND the OAuth `files/{id}?alt=media` alias) + podcast audio
     *  urls. Drives BOTH the "Offline" badge (direct mediaUri membership) and the
     *  Download-vs-Delete action ([offlineStateOf] via driveIdOf). */
    val downloadedKeys: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        offlineMediaManager.downloadedKeys.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptySet()
        )

    private val _offlineStatus = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val offlineStatus: kotlinx.coroutines.flow.SharedFlow<String> = _offlineStatus

    /** mediaUri → DownloadProgressBus id for downloads STARTED from this screen,
     *  so the row shows a live progress bar + STOP (✕) while it is in flight. */
    private val _downloadingIds = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadingIds: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _downloadingIds

    /** Offline state of a Last Played row given a downloadedKeys snapshot. */
    fun offlineStateOf(
        item: LastPlayedRepository.HistoryItem,
        keys: Set<String>
    ): com.powermediaplayer.offline.OfflineState = offlineMediaManager.stateOf(
        item.mediaUri,
        item.source == LastPlayedRepository.Source.SPOTIFY,
        keys,
        item.source.name
    )

    fun downloadOffline(item: LastPlayedRepository.HistoryItem) {
        viewModelScope.launch {
            val pid = offlineMediaManager.progressIdFor(item.mediaUri)
            if (pid != null) _downloadingIds.value = _downloadingIds.value + (item.mediaUri to pid)
            _offlineStatus.tryEmit("Downloading: ${item.title}…")
            try {
                val r = offlineMediaManager.download(item.mediaUri, item.title)
                _offlineStatus.tryEmit(
                    if (r.isSuccess) "Saved offline: ${item.title}"
                    else r.exceptionOrNull()?.message ?: "Download failed"
                )
            } finally {
                _downloadingIds.value = _downloadingIds.value - item.mediaUri
            }
        }
    }

    fun deleteOffline(item: LastPlayedRepository.HistoryItem) {
        viewModelScope.launch {
            val r = offlineMediaManager.deleteLocal(item.mediaUri)
            _offlineStatus.tryEmit(
                if (r.isSuccess) "Deleted local copy: ${item.title}"
                else r.exceptionOrNull()?.message ?: "Nothing to delete"
            )
        }
    }

    /**
     * Transient user-visible messages emitted by failure paths in this
     * VM (e.g. Spotify Connect has no active device, token expired).
     * Collected by [LastPlayedScreen] and surfaced via SnackbarHost.
     * `extraBufferCapacity = 4` so a rapid burst (token-expired + no-
     * device + manual replay) doesn't drop the first emission.
     */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // vc32: per-instance resume counters were themselves a bug —
    // destination-scoped ViewModels are cleared on back-stack pop, so
    // every tab re-entry reset them. Debounce + staleness now live in
    // the process-wide com.powermediaplayer.playback.ResumeGate.

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
            // vc32: parse-bearing path — token so a newer play
            // intent supersedes it.
            val token = com.powermediaplayer.playback.ResumeGate.begin()
            // vc32: banner over the pre-player parse phase.
            playbackConnection.setCloudFetchInProgress(true)
            try {
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
                if (!com.powermediaplayer.playback.ResumeGate.isCurrent(token)) {
                    com.powermediaplayer.diag.DiagLog.resume(
                        "playAlbumTrack ABORT token=$token — superseded"
                    )
                    return@launch
                }
                playbackConnection.setMediaItems(listOf(mediaItem), 0)
            } finally {
                playbackConnection.setCloudFetchInProgress(false)
                com.powermediaplayer.playback.ResumeGate.end(token)
            }
        }
    }

    /** Pin a Recents row by its session id. False on full (10/10). */
    suspend fun pinSession(historyId: Long, followLive: Boolean = false): Boolean =
        repo.pinSession(historyId, followLive).isSuccess

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

    // vc31 — last swipe-deleted Recents row (+ its bookmarks), held for a
    // single-level Undo from the snackbar. Cleared once consumed.
    private var lastDeletedRecent:
        Pair<com.powermediaplayer.data.db.entity.PlaybackHistoryEntity,
            List<com.powermediaplayer.data.db.entity.HistoryBookmarkEntity>>? = null

    fun deleteRecentsRow(historyId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            lastDeletedRecent = repo.snapshotForUndo(historyId)
            repo.delete(historyId)
        }
    }

    /** Restore the most-recent swipe-deleted Recents row (snackbar Undo). */
    fun undoDeleteRecentsRow() {
        val snap = lastDeletedRecent ?: return
        lastDeletedRecent = null
        viewModelScope.launch(Dispatchers.IO) { repo.restoreRow(snap.first, snap.second) }
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
        // #19 — a follow-live star resumes from the LIVE playback_history
        // position (resolved now), not the pin-time snapshot. Resolve it off the
        // Main thread, then RE-ENTER with it as an explicit position so the play
        // path below is byte-for-byte unchanged. Fixed stars (followLive=false)
        // and Recents skip this entirely → zero behaviour change.
        if (item.followLive && atPositionMs == null) {
            viewModelScope.launch(Dispatchers.IO) {
                val live = runCatching { repo.livePositionForUri(item.mediaUri) }.getOrNull()
                val resolved = com.powermediaplayer.data.repository.StarPositionResolver.resolve(
                    followLive = true, snapshotMs = item.lastPositionMs, liveMs = live
                ).coerceAtLeast(0L)
                com.powermediaplayer.diag.DiagLog.resume(
                    "star follow-live resolve snapshot=${item.lastPositionMs} live=$live -> $resolved"
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    playLocalAt(item, atPositionMs = resolved)
                }
            }
            return
        }
        // vc32: the vc31 hard tap-IGNORE is gone — it let the
        // cold-start restore's token swallow user taps for the whole of
        // its parse. Stacked loads are prevented by ResumeGate generation
        // supersession instead: every tap begins a new generation and any
        // in-flight resume aborts before touching the player — last tap
        // wins, nobody is locked out.
        if (com.powermediaplayer.playback.ResumeGate.activeCount() > 0) {
            com.powermediaplayer.diag.DiagLog.resume(
                "tap SUPERSEDES in-flight resume " +
                    "(active=${com.powermediaplayer.playback.ResumeGate.activeCount()})"
            )
        }
        // Spotify rows: route via Spotify Connect, including the
        // bookmark override position. Polling starts unconditionally on
        // play success (the previous gating-on-positive-position bug
        // left the Player tab showing stale local metadata when a
        // Spotify entry was tapped from a fresh-position row).
        if (item.source == LastPlayedRepository.Source.SPOTIFY) {
            val targetPos = atPositionMs ?: item.lastPositionMs
            // vc31 — instrument the Spotify resume branch with the same
            // timing detail as local/Drive so every source path is
            // traceable in the diagnostic log.
            com.powermediaplayer.diag.DiagLog.resume(
                "tap activeBefore=${com.powermediaplayer.playback.ResumeGate.activeCount()} " +
                    "source=SPOTIFY uri=${com.powermediaplayer.diag.DiagLog.hash(item.mediaUri)} " +
                    "targetPos=${targetPos}ms"
            )
            // Stop the local ExoPlayer first — otherwise tapping a
            // Spotify row from Last Played leaves any currently-playing
            // local audio audible behind the Spotify Connect track.
            runCatching { playbackConnection.pause() }
            // vc32: provisional mirror AT TAP TIME — controls route
            // to Spotify immediately and the UI shows the REQUESTED track,
            // closing the gap where play/pause resumed the local player.
            spotifyProvider.armProvisionalMirror(
                com.powermediaplayer.cloud.SpotifyPlaybackState(
                    title = item.title,
                    artist = item.subtitle,
                    album = "",
                    artworkUrl = item.artworkUri,
                    positionMs = targetPos.coerceAtLeast(0L),
                    durationMs = item.durationMs,
                    isPlaying = true,
                    trackUri = item.mediaUri,
                    deviceName = null
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val token = com.powermediaplayer.playback.ResumeGate.begin()
                val tStart = com.powermediaplayer.diag.DiagLog.now()
                com.powermediaplayer.diag.DiagLog.resume(
                    "spotify coroutine START token=$token " +
                        "activeNow=${com.powermediaplayer.playback.ResumeGate.activeCount()}"
                )
                try {
                val tPlay = com.powermediaplayer.diag.DiagLog.now()
                // A Spotify album/playlist/show URI is a CONTEXT, not a track —
                // it must play via context_uri (PUT /play {context_uri:…}), NOT
                // the uris:[…] field, which only accepts track/episode URIs (an
                // album URI there → Spotify 400 "Unsupported uri kind: album").
                // Meteora was saved to Recents as spotify:album:… by the album
                // play, so replaying it hit exactly that 400.
                val isContextUri = item.mediaUri.startsWith("spotify:album:") ||
                    item.mediaUri.startsWith("spotify:playlist:") ||
                    item.mediaUri.startsWith("spotify:show:")
                val play = runCatching {
                    if (isContextUri)
                        spotifyProvider.playTrackOnConnectDevice(spotifyUri = null, contextUri = item.mediaUri)
                    else
                        spotifyProvider.playTrackOnConnectDevice(item.mediaUri, contextUri = null)
                }.getOrNull()
                com.powermediaplayer.diag.DiagLog.perf(
                    "resume.spotifyPlayCall", com.powermediaplayer.diag.DiagLog.now() - tPlay,
                    "token=$token ok=${play?.isSuccess == true}"
                )
                val ok = play?.isSuccess == true
                if (ok) {
                    // Album/playlist/show replay → loop the context to the start
                    // at the end, matching the album-play behaviour.
                    if (isContextUri) runCatching { spotifyProvider.setRepeat("context") }
                    // Always start polling so the Player tab swaps to
                    // the Spotify mirror — independent of whether the
                    // user is jumping to a saved position. expectPlayback
                    // arms the vc32 handoff grace so the loading banner
                    // survives the device-wake null snaps.
                    spotifyProvider.startPlaybackPolling(
                        expectPlayback = true, expectedTrack = item.mediaUri
                    )
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
                    // vc32: never leave a provisional mirror for a
                    // track that failed to play.
                    spotifyProvider.clearProvisionalMirror()
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
                } finally {
                    com.powermediaplayer.playback.ResumeGate.end(token)
                    com.powermediaplayer.diag.DiagLog.resume(
                        "spotify coroutine END token=$token " +
                            "totalElapsed=${com.powermediaplayer.diag.DiagLog.now() - tStart}ms " +
                            "stillActive=${com.powermediaplayer.playback.ResumeGate.activeCount()}"
                    )
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
        com.powermediaplayer.diag.DiagLog.resume(
            "tap activeBefore=${com.powermediaplayer.playback.ResumeGate.activeCount()} " +
                "source=${item.source} uri=${com.powermediaplayer.diag.DiagLog.hash(item.mediaUri)} " +
                "scheme=${uri.scheme} targetPos=${targetPos}ms"
        )
        // M4bChapterParser opens MediaExtractor + walks the full MP4 box
        // hierarchy synchronously. For multi-GB Drive-backed audiobooks
        // accessed via content:// SAF this routinely blocked the Main
        // thread > 5 s → ANR. Mirror LibraryViewModel.playSingle: build
        // the MediaItem on Dispatchers.IO, then hop back to Main for
        // the MediaController calls.
        viewModelScope.launch {
            val token = com.powermediaplayer.playback.ResumeGate.begin()
            val tStart = com.powermediaplayer.diag.DiagLog.now()
            com.powermediaplayer.diag.DiagLog.resume(
                "coroutine START token=$token " +
                    "activeNow=${com.powermediaplayer.playback.ResumeGate.activeCount()}"
            )
            // vc32: banner over the pre-player parse phase — on slow
            // remote items the dominant wait happens BEFORE ExoPlayer
            // ever starts loading, so neither the cloud-browse flag nor
            // isLoading covers it. Cleared in finally so a failed parse
            // can't strand the banner.
            playbackConnection.setCloudFetchInProgress(true)
            try {
                // vc32: remote schemes are NEVER parsed inline — both
                // parser strategies end up streaming most of the file.
                // Playback starts immediately (the URL is range-capable);
                // chapters arrive via the async fill-in below.
                val isRemote = com.powermediaplayer.util.M4bChapterParser.isRemote(uri)
                // Reverse mode: local audio + Drive files up to the 50 MB
                // size guard (download cost; cached after the first run).
                // Spotify never enters our pipeline.
                val reversed = settingsDataStore.audioReverseLocal.first()
                val playUri = if (reversed) {
                    withContext(Dispatchers.IO) {
                        com.powermediaplayer.audio.ReverseAudio
                            .ensureReversedWav(context, uri)
                            .map { android.net.Uri.fromFile(it) }
                            .onFailure {
                                com.powermediaplayer.diag.DiagLog.resume(
                                    "reverse failed — playing forward: ${it.message}"
                                )
                            }
                            .getOrDefault(uri)
                    }
                } else {
                    // §C10/§C28 — true offline: tap a downloaded podcast OR Drive
                    // item in Recents → play the LOCAL file (instant, offline; the
                    // mediaId/key stays the stream url so the resume point matches).
                    withContext(Dispatchers.IO) {
                        podcastOfflineResolver.localUriFor(item.mediaUri)
                            ?: driveOfflineResolver.localUriFor(item.mediaUri)
                    } ?: uri
                }
                val mediaItem = withContext(Dispatchers.IO) {
                    val tParse = com.powermediaplayer.diag.DiagLog.now()
                    val chapterExtras = if (isRemote || reversed) {
                        if (reversed) android.os.Bundle()
                        else com.powermediaplayer.util.M4bChapterParser.cachedOnly(context, uri)
                            ?: android.os.Bundle()
                    } else {
                        runCatching {
                            com.powermediaplayer.util.M4bChapterParser
                                .extractChaptersAsBundle(context, uri)
                        }.getOrDefault(android.os.Bundle())
                    }
                    com.powermediaplayer.diag.DiagLog.perf(
                        "resume.chapterExtract", com.powermediaplayer.diag.DiagLog.now() - tParse,
                        "token=$token remote=$isRemote chapterCount=${chapterExtras.getInt("chapter_count", -1)}"
                    )
                    androidx.media3.common.MediaItem.Builder()
                        .setMediaId(item.mediaUri)
                        .setUri(playUri)
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
                // vc32: a stale resume must never touch the player — a
                // superseded load would otherwise auto-play underneath
                // whatever the user has since switched to.
                if (!com.powermediaplayer.playback.ResumeGate.isCurrent(token)) {
                    com.powermediaplayer.diag.DiagLog.resume(
                        "coroutine ABORT token=$token — superseded"
                    )
                    return@launch
                }
                val tSet = com.powermediaplayer.diag.DiagLog.now()
                playbackConnection.setMediaItems(
                    listOf(mediaItem), 0,
                    startPositionMs = if (reversed) 0L else targetPos
                )
                com.powermediaplayer.diag.DiagLog.perf(
                    "resume.setMediaItems+seek", com.powermediaplayer.diag.DiagLog.now() - tSet,
                    "token=$token reversed=$reversed"
                )
                // vc32: background chapter fill-in for remote
                // items — parses once, caches (including the empty
                // result), and injects via the existing setLocalChapters
                // path IF the user is still on this item.
                if (mediaItem.mediaMetadata.extras?.getInt("chapter_count", 0) == 0) {
                    if (item.source == com.powermediaplayer.data.repository
                            .LastPlayedRepository.Source.DRIVE
                    ) {
                        // Drive: the moov (tags + chapters) is often at the END
                        // of the file and CANNOT be read over the https URL —
                        // the old async remote parse took ~75 s and returned 0
                        // chapters. Download + parse the LOCAL file instead
                        // (loads the proper title/author too) and PERSIST, so
                        // this and every later resume show it. Same enricher the
                        // Cloud tab uses → tapping from either place is consistent.
                        val driveId = if (item.mediaUri.startsWith("content://")) item.mediaUri
                            else item.mediaUri.substringAfter("/files/", "").substringBefore("?")
                                .ifEmpty { item.mediaUri.hashCode().toString() }
                        driveTagEnricher.enrich(
                            viewModelScope,
                            com.powermediaplayer.cloud.CloudMediaItem(
                                id = driveId,
                                name = item.title,
                                mimeType = "audio/mp4",
                                size = 0L,
                                downloadUrl = item.mediaUri,
                                sourceProvider = com.powermediaplayer.cloud.CloudProviderType.GOOGLE_DRIVE
                            ),
                            item.mediaUri
                        )
                    } else if (isRemote) {
                        // Non-Drive remote (rare): keep the best-effort inline
                        // remote parse (range-capable URLs can stream-parse).
                        viewModelScope.launch(Dispatchers.IO) {
                            if (!com.powermediaplayer.util.ChapterCache.shared.markFilling(item.mediaUri)) {
                                return@launch
                            }
                            val tFill = com.powermediaplayer.diag.DiagLog.now()
                            val late = try {
                                runCatching {
                                    com.powermediaplayer.util.M4bChapterParser
                                        .extractChaptersAsBundle(context, uri)
                                }.getOrDefault(android.os.Bundle())
                            } finally {
                                com.powermediaplayer.util.ChapterCache.shared
                                    .unmarkFilling(item.mediaUri)
                            }
                            val count = late.getInt("chapter_count", 0)
                            com.powermediaplayer.diag.DiagLog.perf(
                                "resume.asyncChapterFill",
                                com.powermediaplayer.diag.DiagLog.now() - tFill,
                                "token=$token count=$count"
                            )
                            if (count > 0 &&
                                com.powermediaplayer.playback.ResumeGate.isCurrent(token)
                            ) {
                                val chapters = (0 until count).mapNotNull { i ->
                                    val title = late.getString("chapter_title_$i")
                                        ?: "Chapter ${i + 1}"
                                    val start = late.getLong("chapter_start_$i", -1)
                                    val end = late.getLong("chapter_end_$i", -1)
                                    if (start >= 0) {
                                        com.powermediaplayer.service.ChapterInfo(title, start, end, i)
                                    } else null
                                }
                                playbackConnection.setLocalChapters(chapters)
                            }
                        }
                    }
                }
            } finally {
                playbackConnection.setCloudFetchInProgress(false)
                com.powermediaplayer.playback.ResumeGate.end(token)
                com.powermediaplayer.diag.DiagLog.resume(
                    "coroutine END token=$token totalElapsed=${com.powermediaplayer.diag.DiagLog.now() - tStart}ms " +
                        "stillActive=${com.powermediaplayer.playback.ResumeGate.activeCount()}"
                )
            }
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
