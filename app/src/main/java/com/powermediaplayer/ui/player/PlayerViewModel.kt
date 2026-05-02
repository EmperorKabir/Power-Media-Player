package com.powermediaplayer.ui.player

import android.media.AudioManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.service.PlayerState
import com.powermediaplayer.util.TextNormalizer
import com.powermediaplayer.util.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * ViewModel for the Player screen.
 * Transforms raw PlayerState from PlaybackConnection into display-ready PlayerUiState.
 * Manages sleep timer countdown. All state updates flow via StateFlow to prevent
 * frame drops from the 12+ simultaneous on-screen buttons.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Sleep timer state
    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)

    /**
     * The single source of truth for the player UI.
     * Combines PlaybackConnection state with computed display values.
     */
    val uiState: StateFlow<PlayerUiState> = combine(
        playbackConnection.playerState,
        _sleepTimerRemainingMs
    ) { playerState, sleepRemaining ->
        mapToUiState(playerState, sleepRemaining)
    }.stateIn(
        scope = viewModelScope,
        // Eagerly keeps the combiner running so navigation to the player
        // tab finds the latest state already mapped — eliminates the
        // brief WhileSubscribed initial-value flash that swapped the
        // layout between Expanded (audio default) and Compact (video).
        started = SharingStarted.Eagerly,
        initialValue = mapToUiState(playbackConnection.playerState.value, 0L)
    )

    /**
     * Reactive Player reference — updates when the MediaController finishes connecting.
     * MUST be collected as state in the UI (not captured with `remember`) so that
     * VideoSurface attaches correctly after the async connection completes.
     */
    val playerFlow = playbackConnection.playerFlow

    /**
     * Cover-art bytes carried in their own flow (not in PlayerUiState),
     * because ByteArray is unstable in Compose. Keeping it inside the
     * UI state would force the entire player tree to recompose on every
     * 500 ms position-poll tick. Distinct value emissions only — when
     * the underlying reference is unchanged StateFlow conflates.
     */
    val artworkBytes: StateFlow<ByteArray?> = playbackConnection.playerState
        .map { it.artworkBytes }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = playbackConnection.playerState.value.artworkBytes
        )


    // ── Transport Controls (delegated to PlaybackConnection) ─────

    fun clearError() = playbackConnection.clearError()

    fun playPause() = playbackConnection.playPause()
    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)
    fun seekToNext() = playbackConnection.seekToNext()
    fun seekToPrevious() = playbackConnection.seekToPrevious()
    fun skipBack(seconds: Int) {
        android.util.Log.i("PMP_DIAG", "VM.skipBack(${seconds}s)")
        playbackConnection.skipBack(seconds)
    }
    fun skipForward(seconds: Int) {
        android.util.Log.i("PMP_DIAG", "VM.skipForward(${seconds}s)")
        playbackConnection.skipForward(seconds)
    }
    fun nextChapter() = playbackConnection.nextChapter()
    fun previousChapter() = playbackConnection.previousChapter()
    fun nextChapterOrTrack() = playbackConnection.nextChapterOrTrack()
    fun previousChapterOrTrack() = playbackConnection.previousChapterOrTrack()
    fun nextFile() = playbackConnection.nextFile()
    fun previousFile() = playbackConnection.previousFile()
    fun seekToChapter(index: Int) = playbackConnection.seekToChapterIndex(index)

    fun setPlaybackSpeed(speed: Float) = playbackConnection.setPlaybackSpeed(speed)

    // ── Volume (mapped to AudioManager system volume) ────────────

    fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun getCurrentVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun setVolume(volume: Int) {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volume.coerceIn(0, getMaxVolume()),
            0 // No flags (no UI toast)
        )
    }

    // ── Sleep Timer ──────────────────────────────────────────────

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val totalMs = minutes * 60_000L
        _sleepTimerRemainingMs.value = totalMs

        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0)
            }
            // Timer expired — pause playback
            playbackConnection.pause()
            _sleepTimerRemainingMs.value = 0
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMs.value = 0
    }

    // ── Playlist Seek (seek within entire playlist by absolute position) ──

    fun seekToPlaylistPosition(absolutePositionMs: Long) =
        playbackConnection.seekToAbsolutePlaylistPosition(absolutePositionMs)

    // ── Set media items for playback ─────────────────────────────

    fun setMediaItems(items: List<androidx.media3.common.MediaItem>, startIndex: Int = 0) {
        playbackConnection.setMediaItems(items, startIndex)
    }

    // ── State Mapping ────────────────────────────────────────────

    private fun mapToUiState(playerState: PlayerState, sleepRemainingMs: Long): PlayerUiState {
        val hasMedia = playerState.mediaItemCount > 0

        // Chapter-relative track slider — when the file has chapters, the
        // track slider scrubs the CURRENT chapter (start..end) so the user
        // sees per-chapter progress and remaining; the full slider shows
        // overall file/playlist progress separately.
        val currentChapter = playerState.chapters.getOrNull(playerState.currentChapterIndex)
        val inChapter = playerState.hasChapters && currentChapter != null
        val chapterStart = if (inChapter) currentChapter!!.startTimeMs else 0L
        val chapterEnd = if (inChapter) currentChapter!!.endTimeMs else playerState.duration
        val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0L)
        val chapterPos = (playerState.currentPosition - chapterStart).coerceIn(0L, chapterDuration)

        val trackProgress = if (inChapter && chapterDuration > 0) {
            (chapterPos.toFloat() / chapterDuration.toFloat()).coerceIn(0f, 1f)
        } else if (playerState.duration > 0) {
            (playerState.currentPosition.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val playlistProgress = if (playerState.totalPlaylistDuration > 0) {
            (playerState.totalPlaylistPosition.toFloat() / playerState.totalPlaylistDuration.toFloat()).coerceIn(0f, 1f)
        } else 0f

        // Track-slider numerator/denominator follow the chapter scope when
        // available, so the displayed times match what the slider shows.
        val displayedTrackPos = if (inChapter) chapterPos else playerState.currentPosition
        val displayedTrackDur = if (inChapter) chapterDuration else playerState.duration
        val trackRemaining = (displayedTrackDur - displayedTrackPos).coerceAtLeast(0L)
        val playlistRemaining = (playerState.totalPlaylistDuration - playerState.totalPlaylistPosition).coerceAtLeast(0L)
        return PlayerUiState(
            isPlaying = playerState.isPlaying,
            isLoading = playerState.isLoading,
            // Normalize all human-visible strings to repair UTF-8/Latin-1
            // mojibake (e.g. "Philosopherâ€™s" → "Philosopher's") and
            // collapse curly quotes / invisible formatting.
            title = TextNormalizer.normalize(playerState.title).ifEmpty { "No media loaded" },
            artist = TextNormalizer.normalize(playerState.artist),
            album = TextNormalizer.normalize(playerState.album),
            description = TextNormalizer.normalize(playerState.description),
            artworkUri = playerState.artworkUri,
            hasCoverArt = playerState.hasCoverArt,
            currentPosition = displayedTrackPos,
            duration = displayedTrackDur,
            currentPositionFormatted = TimeFormatter.formatDuration(displayedTrackPos),
            durationFormatted = TimeFormatter.formatDuration(displayedTrackDur),
            trackRemainingFormatted = "-" + TimeFormatter.formatDuration(trackRemaining),
            trackProgress = trackProgress,
            chapterStartMs = chapterStart,
            chapterDurationMs = chapterDuration,
            playerError = playerState.playerError,
            cloudFetchInProgress = playerState.cloudFetchInProgress,
            videoWidth = playerState.videoWidth,
            videoHeight = playerState.videoHeight,
            totalPlaylistPosition = playerState.totalPlaylistPosition,
            totalPlaylistDuration = playerState.totalPlaylistDuration,
            playlistPositionFormatted = TimeFormatter.formatDuration(playerState.totalPlaylistPosition),
            playlistDurationFormatted = TimeFormatter.formatDuration(playerState.totalPlaylistDuration),
            playlistRemainingFormatted = "-" + TimeFormatter.formatDuration(playlistRemaining),
            playlistProgress = playlistProgress,
            playbackSpeed = playerState.playbackSpeed,
            sleepTimerRemainingMs = sleepRemainingMs,
            sleepTimerActive = sleepRemainingMs > 0,
            sleepTimerFormatted = if (sleepRemainingMs > 0) TimeFormatter.formatDuration(sleepRemainingMs) else "",
            currentTrackIndex = playerState.currentMediaItemIndex,
            totalTracks = playerState.mediaItemCount,
            trackIndexDisplay = if (playerState.mediaItemCount > 1) {
                "${playerState.currentMediaItemIndex + 1} / ${playerState.mediaItemCount}"
            } else "",
            chapters = playerState.chapters.map { it.copy(title = TextNormalizer.normalize(it.title)) },
            currentChapterIndex = playerState.currentChapterIndex,
            hasChapters = playerState.hasChapters,
            controls = ControlsEnabledState(
                previousTrack = playerState.hasPrevious,
                nextTrack = playerState.hasNext,
                previousChapter = playerState.hasChapters && playerState.currentChapterIndex > 0,
                nextChapter = playerState.hasChapters && playerState.currentChapterIndex < playerState.chapters.size - 1,
                previousFile = playerState.hasPrevious,
                nextFile = playerState.hasNext,
                previousChapterOrTrack = (playerState.hasChapters && playerState.currentChapterIndex > 0) || playerState.hasPrevious || hasMedia,
                nextChapterOrTrack = (playerState.hasChapters && playerState.currentChapterIndex < playerState.chapters.size - 1) || playerState.hasNext,
                skipBack5 = hasMedia && playerState.isSeekable,
                skipBack10 = hasMedia && playerState.isSeekable,
                skipBack15 = hasMedia && playerState.isSeekable,
                skipBack20 = hasMedia && playerState.isSeekable,
                skipBack30 = hasMedia && playerState.isSeekable,
                skipForward5 = hasMedia && playerState.isSeekable,
                skipForward10 = hasMedia && playerState.isSeekable,
                skipForward15 = hasMedia && playerState.isSeekable,
                skipForward20 = hasMedia && playerState.isSeekable,
                skipForward30 = hasMedia && playerState.isSeekable,
                playPause = hasMedia,
                playbackSpeed = hasMedia,
                brightness = true,
                volume = true,
                sleepTimer = hasMedia,
                trackSlider = hasMedia && playerState.duration > 0 && playerState.isSeekable,
                playlistSlider = playerState.isPartOfPlaylist && playerState.totalPlaylistDuration > 0 && playerState.isSeekable
            ),
            isVideoContent = playerState.isVideoContent
        )
    }

    /**
     * Provides direct access to the underlying Player for VideoSurface attachment.
     */
    fun getPlayer() = playbackConnection.getPlayer()
}
