package com.powermediaplayer.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing the complete player state exposed to the UI.
 */
data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPercentage: Int = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val artworkBytes: ByteArray? = null,
    val artworkBitmap: Bitmap? = null,
    val playbackSpeed: Float = 1.0f,
    val currentMediaItemIndex: Int = 0,
    val mediaItemCount: Int = 0,
    val isLoading: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    // Playlist/album total duration tracking
    val totalPlaylistDuration: Long = 0L,
    val totalPlaylistPosition: Long = 0L,
    // Chapter support
    val chapters: List<ChapterInfo> = emptyList(),
    val currentChapterIndex: Int = -1,
    val hasChapters: Boolean = false,
    // Long-form description extracted from ID3/Vorbis/MP4 metadata at runtime
    val description: String = "",
    // Last playback error (network 401, codec failure, etc.) — null when ok.
    val playerError: String? = null,
    // True while a cloud cache download (chapters/metadata extraction)
    // is in flight, so the UI can show a non-blocking progress hint.
    val cloudFetchInProgress: Boolean = false,
    // Video frame size (used by VideoSurface for aspect-ratio sizing).
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    // Media capabilities for button greying
    val isPartOfPlaylist: Boolean = false,
    val hasCoverArt: Boolean = false,
    val isVideoContent: Boolean = false,
    val isSeekable: Boolean = false
)

/**
 * Represents a chapter/section within a media item.
 */
data class ChapterInfo(
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val index: Int
)

/**
 * Session-level metadata override for cases where the player's own metadata
 * is missing/incomplete (e.g. cloud streams whose tags can only be read
 * after authenticated download).
 */
data class LocalMetadataOverride(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: Uri? = null,
    val artworkBytes: ByteArray? = null
)

/**
 * Singleton connection manager between the UI and PlaybackService.
 * Manages the MediaController lifecycle and exposes reactive player state via StateFlow.
 * Position updates use coroutine polling (250ms) to avoid callback storms on the UI thread.
 */
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv",
    "3gp", "3gpp", "mpg", "mpeg", "ts", "mts", "ogv"
)

@Singleton
class PlaybackConnection @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionPollingJob: Job? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /**
     * Folder-mode override. When non-null, [extractChapters] returns this
     * list verbatim (chapters use absolute folder-wide timestamps and
     * [ChapterInfo.index] holds the target media-item index). Set via
     * [setFolderChapters] from the library before playFolder.
     */
    private var folderChapters: List<ChapterInfo>? = null

    /**
     * Single-track session override — used when we extract chapters AFTER
     * playback starts (e.g. Drive M4Bs whose moov box is only readable once
     * the file has been downloaded with auth). Timestamps are relative to
     * the current track. Cleared on every [setMediaItems] call.
     */
    private var localChapters: List<ChapterInfo>? = null

    /**
     * Title/artist/album/artwork override surfaced from a post-load metadata
     * scan (Drive items pull these from the downloaded cache file via
     * MediaMetadataRetriever). Cleared on [setMediaItems].
     */
    private var localMetadata: LocalMetadataOverride? = null

    /**
     * Authoritative video flag set by callers that already know the content
     * type (LibraryViewModel.playFiles when file.isVideo, CloudViewModel
     * when item.mimeType starts with "video/"). Bypasses every fragile
     * metadata-round-trip detection path. Cleared on [setMediaItems].
     */
    private var videoModeHint: Boolean = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Reactive player reference — updates when the MediaController connects/disconnects.
    // Collected from the UI so VideoSurface attaches after the async connect completes.
    private val _playerFlow = MutableStateFlow<Player?>(null)
    val playerFlow: StateFlow<Player?> = _playerFlow.asStateFlow()

    /**
     * Connect to the PlaybackService. Call from Activity onCreate.
     */
    fun connect() {
        if (controllerFuture != null) return

        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken)
            .buildAsync()

        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                _playerFlow.value = controller       // expose reactively
                _isConnected.value = true
                setupPlayerListener()
                startPositionPolling()
                updatePlayerState()
            } catch (e: Exception) {
                _isConnected.value = false
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Disconnect from the PlaybackService. Call from Activity onDestroy.
     */
    fun disconnect() {
        positionPollingJob?.cancel()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controller = null
        _playerFlow.value = null
        controllerFuture = null
        _isConnected.value = false
    }

    /**
     * Exposes the underlying Player for video surface attachment.
     * Prefer collecting [playerFlow] for reactive updates.
     */
    fun getPlayer(): Player? = controller

    // ── Transport Controls ───────────────────────────────────────

    fun play() { controller?.play() }
    fun pause() { controller?.pause() }
    fun playPause() {
        controller?.let { c ->
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun seekToNext() { controller?.seekToNextMediaItem() }
    fun seekToPrevious() { controller?.seekToPreviousMediaItem() }

    fun skipBack(seconds: Int) {
        val action = when (seconds) {
            5 -> PlaybackService.ACTION_SKIP_BACK_5
            10 -> PlaybackService.ACTION_SKIP_BACK_10
            15 -> PlaybackService.ACTION_SKIP_BACK_15
            20 -> PlaybackService.ACTION_SKIP_BACK_20
            30 -> PlaybackService.ACTION_SKIP_BACK_30
            else -> return
        }
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun skipForward(seconds: Int) {
        val action = when (seconds) {
            5 -> PlaybackService.ACTION_SKIP_FORWARD_5
            10 -> PlaybackService.ACTION_SKIP_FORWARD_10
            15 -> PlaybackService.ACTION_SKIP_FORWARD_15
            20 -> PlaybackService.ACTION_SKIP_FORWARD_20
            30 -> PlaybackService.ACTION_SKIP_FORWARD_30
            else -> return
        }
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackParameters(PlaybackParameters(speed))
    }

    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * Set media items and start playback. Clears any folder-chapter override
     * because callers using setMediaItems() are not in folder mode.
     */
    fun setMediaItems(items: List<MediaItem>, startIndex: Int = 0) {
        folderChapters = null
        localChapters = null
        localMetadata = null
        videoModeHint = false
        controller?.let { c ->
            c.setMediaItems(items, startIndex, 0L)
            c.prepare()
            c.play()
        }
    }

    /**
     * Caller-supplied authoritative video flag. Call BEFORE [setMediaItems]
     * to keep the flag through the queue swap, or AFTER to flip an in-flight
     * track's layout. Either way it persists until the next [setMediaItems].
     */
    fun setVideoModeHint(isVideo: Boolean) {
        videoModeHint = isVideo
        updatePlayerStateOnMain()
    }

    /**
     * Lightweight progress flag for callers that download cloud files in
     * the background (chapter / metadata extraction). Surfaced through
     * [PlayerState.cloudFetchInProgress] so the UI can show "Loading…".
     */
    fun setCloudFetchInProgress(inProgress: Boolean) {
        if (_playerState.value.cloudFetchInProgress != inProgress) {
            _playerState.value = _playerState.value.copy(cloudFetchInProgress = inProgress)
        }
    }

    /**
     * Provide title/artist/album/artwork extracted post-load (e.g. from a
     * Drive download). null clears.
     */
    fun setLocalMetadata(meta: LocalMetadataOverride?) {
        localMetadata = meta
        updatePlayerStateOnMain()
    }

    /**
     * Provide chapters discovered after playback started (e.g. Drive M4B
     * whose chapter atoms required an authenticated download). Pass null
     * (or empty) to clear; supersedes per-track extras-based chapters but
     * is itself superseded by folder mode.
     *
     * SAFE FROM ANY THREAD — reschedules updatePlayerState onto main since
     * MediaController.getMediaMetadata is main-thread-only.
     */
    fun setLocalChapters(chapters: List<ChapterInfo>?) {
        localChapters = chapters?.takeIf { it.isNotEmpty() }
        updatePlayerStateOnMain()
    }

    /**
     * Wrapper that ensures updatePlayerState always runs on the main thread.
     * Background coroutines (Drive download, parser) call setLocalChapters /
     * setLocalMetadata / setCloudFetchInProgress / setFolderChapters from
     * Dispatchers.IO — the underlying MediaController calls are NOT thread
     * safe, so dispatch back to main first.
     */
    private fun updatePlayerStateOnMain() {
        // scope is Dispatchers.Main.immediate — runs synchronously when
        // already on main, otherwise posts.
        scope.launch { updatePlayerState() }
    }

    /**
     * Set the folder-wide chapter list. Pass null (or empty) to clear and
     * fall back to per-file chapters from MediaItem extras.
     */
    fun setFolderChapters(chapters: List<ChapterInfo>?) {
        folderChapters = chapters?.takeIf { it.isNotEmpty() }
        updatePlayerStateOnMain()
    }

    /**
     * Folder-mode seek: jump to the media item identified by the
     * chapter's [ChapterInfo.index] field, position 0.
     */
    fun seekToFolderChapter(idx: Int) {
        val list = folderChapters ?: return
        val chapter = list.getOrNull(idx) ?: return
        controller?.seekTo(chapter.index, 0L)
    }

    /**
     * Cross-track absolute seek: find which media item contains
     * [absolutePositionMs] within the entire playlist, then seek to
     * that item at the appropriate offset.
     */
    fun seekToAbsolutePlaylistPosition(absolutePositionMs: Long) {
        val c = controller ?: return
        val timeline = c.currentTimeline
        val window = Timeline.Window()
        var cursor = 0L
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            val winDur = window.durationMs
            if (winDur <= 0) continue
            if (absolutePositionMs < cursor + winDur) {
                c.seekTo(i, absolutePositionMs - cursor)
                return
            }
            cursor += winDur
        }
        // Past the end — go to last item end
        if (timeline.windowCount > 0) {
            timeline.getWindow(timeline.windowCount - 1, window)
            c.seekTo(timeline.windowCount - 1, window.durationMs.coerceAtLeast(0L))
        }
    }

    /**
     * Chapter-aware forward navigation. If the current file has chapters and
     * we are not at the last one, seek to the next chapter. Otherwise advance
     * to the next file in the queue. This is the "smart next" button.
     */
    /**
     * Mode-aware seek-to-chapter — folder mode jumps to the right media item;
     * single-file mode seeks within the current track to chapter offset.
     */
    fun seekToChapterIndex(idx: Int) {
        val state = _playerState.value
        val chapter = state.chapters.getOrNull(idx) ?: return
        if (folderChapters != null) {
            seekToFolderChapter(idx)
        } else {
            seekTo(chapter.startTimeMs)
        }
    }

    fun nextChapterOrTrack() {
        val state = _playerState.value
        if (state.hasChapters) {
            val nextIdx = state.currentChapterIndex + 1
            if (nextIdx < state.chapters.size) {
                if (folderChapters != null) {
                    seekToFolderChapter(nextIdx)
                } else {
                    seekTo(state.chapters[nextIdx].startTimeMs)
                }
                return
            }
        }
        if (state.hasNext) seekToNext()
    }

    /**
     * Chapter-aware backward navigation. Mirrors [nextChapterOrTrack] — if at
     * the start of a chapter (within a small grace window), seek to the
     * previous chapter; if at the start of the first chapter, go to previous
     * file. If position is mid-chapter, restart the current chapter.
     */
    fun previousChapterOrTrack() {
        val state = _playerState.value
        val isFolderMode = folderChapters != null
        val absolutePos = controller?.let { calculatePlaylistPosition(it) } ?: 0L
        val trackPos = controller?.currentPosition ?: 0L
        val referencePos = if (isFolderMode) absolutePos else trackPos
        if (state.hasChapters) {
            val current = state.chapters.getOrNull(state.currentChapterIndex)
            if (current != null && referencePos - current.startTimeMs > 3000L) {
                if (isFolderMode) {
                    seekToFolderChapter(state.currentChapterIndex)
                } else {
                    seekTo(current.startTimeMs)
                }
                return
            }
            val prevIdx = state.currentChapterIndex - 1
            if (prevIdx >= 0) {
                if (isFolderMode) {
                    seekToFolderChapter(prevIdx)
                } else {
                    seekTo(state.chapters[prevIdx].startTimeMs)
                }
                return
            }
        } else if (trackPos > 3000L) {
            seekTo(0)
            return
        }
        if (state.hasPrevious) seekToPrevious() else seekTo(0)
    }

    /** Hard file-boundary forward — always next media item, ignores chapters. */
    fun nextFile() {
        if (_playerState.value.hasNext) seekToNext()
    }

    /** Hard file-boundary backward — always previous media item, ignores chapters. */
    fun previousFile() {
        if (_playerState.value.hasPrevious) seekToPrevious() else seekTo(0)
    }

    /**
     * Navigate to next chapter within the current media item only (no fallback).
     * Kept for callers that want strict chapter behaviour.
     */
    fun nextChapter() {
        val state = _playerState.value
        if (!state.hasChapters) return
        val nextIdx = state.currentChapterIndex + 1
        if (nextIdx < state.chapters.size) {
            seekTo(state.chapters[nextIdx].startTimeMs)
        }
    }

    /** Navigate to previous chapter within the current media item only. */
    fun previousChapter() {
        val state = _playerState.value
        if (!state.hasChapters) return
        val prevIdx = state.currentChapterIndex - 1
        if (prevIdx >= 0) {
            seekTo(state.chapters[prevIdx].startTimeMs)
        } else {
            seekTo(0)
        }
    }

    // ── Internal ─────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun setupPlayerListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayerState() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Reset description + previous error — the new track may not emit
                // metadata immediately and shouldn't inherit the prior failure.
                _playerState.value = _playerState.value.copy(description = "", playerError = null)
                updatePlayerState()
            }
            override fun onPlaybackStateChanged(playbackState: Int) { updatePlayerState() }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { updatePlayerState() }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { updatePlayerState() }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { updatePlayerState() }
            override fun onIsLoadingChanged(isLoading: Boolean) { updatePlayerState() }
            // Track changes populate isVideoContent — must be listened to separately
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { updatePlayerState() }
            override fun onMetadata(metadata: Metadata) {
                val desc = extractDescription(metadata)
                if (desc.isNotEmpty() && desc != _playerState.value.description) {
                    _playerState.value = _playerState.value.copy(description = desc)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                _playerState.value = _playerState.value.copy(
                    playerError = error.errorCodeName + ": " + (error.message ?: "Playback failed")
                )
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                _playerState.value = _playerState.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height
                )
            }
        })
    }

    /** Clear any displayed error — typically called when the user dismisses it. */
    fun clearError() {
        if (_playerState.value.playerError != null) {
            _playerState.value = _playerState.value.copy(playerError = null)
        }
    }

    /**
     * Walks ExoPlayer Metadata entries looking for a long-form description.
     * Covers ID3 COMM/TIT3/TDES, Vorbis DESCRIPTION, MP4 ©des/desc/ldes.
     */
    @OptIn(UnstableApi::class)
    private fun extractDescription(metadata: Metadata): String {
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            val text = when (entry) {
                is CommentFrame -> entry.text
                is TextInformationFrame -> if (entry.id == "TIT3" || entry.id == "TDES") {
                    entry.values.firstOrNull().orEmpty()
                } else ""
                is VorbisComment -> if (entry.key.equals("DESCRIPTION", ignoreCase = true)) entry.value else ""
                is MdtaMetadataEntry -> {
                    val key = entry.key
                    if (key == "©des" || key == "desc" || key == "ldes" || key == "©des") {
                        runCatching { String(entry.value, Charsets.UTF_8) }.getOrDefault("")
                    } else ""
                }
                else -> ""
            }
            if (text.isNotBlank()) return text.trim()
        }
        return ""
    }

    /**
     * Poll position every 250ms to smoothly update sliders without callback storms.
     */
    private fun startPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    val currentState = _playerState.value
                    _playerState.value = currentState.copy(
                        currentPosition = c.currentPosition.coerceAtLeast(0L),
                        bufferedPercentage = c.bufferedPercentage,
                        totalPlaylistPosition = calculatePlaylistPosition(c)
                    )
                }
                delay(250)
            }
        }
    }

    /**
     * Full state update from player. Called on discrete events (not position ticks).
     * Preserves [description] (filled by onMetadata) since media metadata doesn't
     * carry it.
     */
    private fun updatePlayerState() {
        val c = controller ?: return
        // Two metadata sources matter here:
        //   - `metadata` = merged player metadata (file-extracted + MediaItem)
        //     — what onMediaMetadataChanged delivers, used for title/artist/etc.
        //   - `itemMetadata` = the unmodified MediaItem.mediaMetadata
        //     — Media3 1.6's combine logic OVERWRITES extras during merge,
        //     so our is_video_hint Boolean only survives on the raw item.
        val metadata = c.mediaMetadata
        val itemMetadata = c.currentMediaItem?.mediaMetadata
        val chapters = extractChapters(c)
        val isFolderMode = folderChapters != null
        val absolutePlaylistPos = calculatePlaylistPosition(c)
        val currentChapter = if (isFolderMode) {
            chapters.indexOfLast { absolutePlaylistPos >= it.startTimeMs }
        } else {
            findCurrentChapter(chapters, c.currentPosition)
        }
        val preservedDescription = _playerState.value.description
        val preservedError = _playerState.value.playerError
        val preservedFetch = _playerState.value.cloudFetchInProgress
        val preservedVw = _playerState.value.videoWidth
        val preservedVh = _playerState.value.videoHeight

        // Detect video by ANY of FIVE signals — any single positive flips
        // the UI to the video layout immediately, so the audio layout never
        // shows for a video file regardless of which signal arrives first:
        //   (1) ExoPlayer's track presence (no isSelected gate — some
        //       devices skip selection until the first frame is decoded)
        //   (2) is_video_hint extra read from the raw MediaItem
        //   (3) URI ends with a recognised video extension
        //   (4) URI path contains "/video/" (MediaStore content URIs have
        //       no extension but always include this segment for video)
        //   (5) localConfiguration MIME starts with "video/"
        val isVideoHint = (itemMetadata?.extras ?: metadata.extras)
            ?.getBoolean("is_video_hint", false) ?: false
        val currentItem = c.currentMediaItem
        val uri = currentItem?.requestMetadata?.mediaUri
            ?: currentItem?.localConfiguration?.uri
        val uriString = uri?.toString().orEmpty()
        val uriExt = uriString.substringAfterLast('.', "").lowercase()
        val isVideoByExt = uriExt in VIDEO_EXTENSIONS
        val isVideoByPath = "/video/" in uriString
        val mime = currentItem?.localConfiguration?.mimeType.orEmpty()
        val isVideoByMime = mime.startsWith("video/")
        val isVideoByTracks = c.currentTracks.groups.any { group ->
            group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
        }
        val hasVideoTrack = videoModeHint || isVideoByTracks || isVideoHint ||
            isVideoByExt || isVideoByPath || isVideoByMime

        // Apply session-level metadata override on top of player metadata.
        val overTitle = localMetadata?.title?.takeIf { it.isNotBlank() }
        val overArtist = localMetadata?.artist?.takeIf { it.isNotBlank() }
        val overAlbum = localMetadata?.album?.takeIf { it.isNotBlank() }
        val overArtwork = localMetadata?.artworkUri
        val overArtworkBytes = localMetadata?.artworkBytes

        _playerState.value = PlayerState(
            isPlaying = c.isPlaying,
            currentPosition = c.currentPosition.coerceAtLeast(0L),
            duration = c.duration.let { if (it == C.TIME_UNSET) 0L else it },
            bufferedPercentage = c.bufferedPercentage,
            title = overTitle ?: metadata.title?.toString() ?: "",
            artist = overArtist ?: metadata.artist?.toString() ?: "",
            album = overAlbum ?: metadata.albumTitle?.toString() ?: "",
            artworkUri = overArtwork ?: metadata.artworkUri,
            artworkBytes = overArtworkBytes ?: metadata.artworkData,
            playbackSpeed = c.playbackParameters.speed,
            currentMediaItemIndex = c.currentMediaItemIndex,
            mediaItemCount = c.mediaItemCount,
            isLoading = c.isLoading,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            totalPlaylistDuration = calculateTotalPlaylistDuration(c),
            totalPlaylistPosition = calculatePlaylistPosition(c),
            chapters = chapters,
            currentChapterIndex = currentChapter,
            hasChapters = chapters.isNotEmpty(),
            description = preservedDescription,
            playerError = preservedError,
            cloudFetchInProgress = preservedFetch,
            videoWidth = preservedVw,
            videoHeight = preservedVh,
            isPartOfPlaylist = c.mediaItemCount > 1,
            hasCoverArt = (overArtwork ?: metadata.artworkUri) != null ||
                (overArtworkBytes ?: metadata.artworkData) != null,
            isVideoContent = hasVideoTrack,
            isSeekable = c.isCurrentMediaItemSeekable
        )
    }

    /**
     * Extract chapter markers. In folder mode the override is returned
     * verbatim (chapters carry absolute folder-wide timestamps). Otherwise
     * we read per-file chapters from the current MediaItem's extras.
     */
    private fun extractChapters(controller: MediaController): List<ChapterInfo> {
        folderChapters?.let { return it }
        localChapters?.let { return it }
        val metadata = controller.mediaMetadata
        val extras = metadata.extras ?: return emptyList()

        val chapterCount = extras.getInt("chapter_count", 0)
        if (chapterCount == 0) return emptyList()

        return (0 until chapterCount).mapNotNull { i ->
            val title = extras.getString("chapter_title_$i") ?: "Chapter ${i + 1}"
            val start = extras.getLong("chapter_start_$i", -1)
            val end = extras.getLong("chapter_end_$i", -1)
            if (start >= 0) {
                ChapterInfo(title = title, startTimeMs = start, endTimeMs = end, index = i)
            } else null
        }
    }

    private fun findCurrentChapter(chapters: List<ChapterInfo>, position: Long): Int {
        return chapters.indexOfLast { position >= it.startTimeMs }
    }

    private fun calculateTotalPlaylistDuration(controller: MediaController): Long {
        var total = 0L
        val timeline = controller.currentTimeline
        val window = Timeline.Window()
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            if (window.durationMs > 0) {
                total += window.durationMs
            }
        }
        return total
    }

    private fun calculatePlaylistPosition(controller: MediaController): Long {
        var position = 0L
        val timeline = controller.currentTimeline
        val window = Timeline.Window()
        for (i in 0 until controller.currentMediaItemIndex) {
            timeline.getWindow(i, window)
            if (window.durationMs > 0) {
                position += window.durationMs
            }
        }
        position += controller.currentPosition.coerceAtLeast(0L)
        return position
    }

}
