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
 * Singleton connection manager between the UI and PlaybackService.
 * Manages the MediaController lifecycle and exposes reactive player state via StateFlow.
 * Position updates use coroutine polling (250ms) to avoid callback storms on the UI thread.
 */
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
     * Set media items and start playback.
     */
    fun setMediaItems(items: List<MediaItem>, startIndex: Int = 0) {
        controller?.let { c ->
            c.setMediaItems(items, startIndex, 0L)
            c.prepare()
            c.play()
        }
    }

    /**
     * Navigate to next chapter within the current media item.
     */
    fun nextChapter() {
        val state = _playerState.value
        if (!state.hasChapters) return
        val nextIdx = state.currentChapterIndex + 1
        if (nextIdx < state.chapters.size) {
            seekTo(state.chapters[nextIdx].startTimeMs)
        }
    }

    /**
     * Navigate to previous chapter within the current media item.
     */
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
                // Reset description — the new track may not emit metadata immediately
                _playerState.value = _playerState.value.copy(description = "")
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
        })
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
        val metadata = c.mediaMetadata
        val chapters = extractChapters(c)
        val currentChapter = findCurrentChapter(chapters, c.currentPosition)
        val preservedDescription = _playerState.value.description

        // Detect video tracks: check all selected tracks for a video track group
        val hasVideoTrack = c.currentTracks.groups.any { group ->
            group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected
        }

        _playerState.value = PlayerState(
            isPlaying = c.isPlaying,
            currentPosition = c.currentPosition.coerceAtLeast(0L),
            duration = c.duration.let { if (it == C.TIME_UNSET) 0L else it },
            bufferedPercentage = c.bufferedPercentage,
            title = metadata.title?.toString() ?: "",
            artist = metadata.artist?.toString() ?: "",
            album = metadata.albumTitle?.toString() ?: "",
            artworkUri = metadata.artworkUri,
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
            isPartOfPlaylist = c.mediaItemCount > 1,
            hasCoverArt = metadata.artworkUri != null || metadata.artworkData != null,
            isVideoContent = hasVideoTrack,
            isSeekable = c.isCurrentMediaItemSeekable
        )
    }

    /**
     * Extract chapter markers from the current media item's timeline window.
     */
    private fun extractChapters(controller: MediaController): List<ChapterInfo> {
        // Media3 doesn't expose chapters directly in all cases.
        // We check the media metadata extras for chapter info.
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
