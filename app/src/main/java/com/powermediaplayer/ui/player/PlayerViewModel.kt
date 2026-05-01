package com.powermediaplayer.ui.player

import android.media.AudioManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.service.PlayerState
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
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlayerUiState()
    )

    /**
     * Reactive Player reference — updates when the MediaController finishes connecting.
     * MUST be collected as state in the UI (not captured with `remember`) so that
     * VideoSurface attaches correctly after the async connection completes.
     */
    val playerFlow = playbackConnection.playerFlow


    // ── Transport Controls (delegated to PlaybackConnection) ─────

    fun playPause() = playbackConnection.playPause()
    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)
    fun seekToNext() = playbackConnection.seekToNext()
    fun seekToPrevious() = playbackConnection.seekToPrevious()
    fun skipBack(seconds: Int) = playbackConnection.skipBack(seconds)
    fun skipForward(seconds: Int) = playbackConnection.skipForward(seconds)
    fun nextChapter() = playbackConnection.nextChapter()
    fun previousChapter() = playbackConnection.previousChapter()
    fun nextChapterOrTrack() = playbackConnection.nextChapterOrTrack()
    fun previousChapterOrTrack() = playbackConnection.previousChapterOrTrack()
    fun nextFile() = playbackConnection.nextFile()
    fun previousFile() = playbackConnection.previousFile()
    fun seekToChapter(index: Int) {
        val chapters = playbackConnection.playerState.value.chapters
        if (index in chapters.indices) playbackConnection.seekTo(chapters[index].startTimeMs)
    }

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

    fun seekToPlaylistPosition(absolutePositionMs: Long) {
        val state = playbackConnection.playerState.value
        // Find which track this absolute position falls within
        var accumulated = 0L
        // We need to iterate through tracks to find the right one
        // For now, this is a simplified version
        playbackConnection.seekTo(absolutePositionMs)
    }

    // ── Set media items for playback ─────────────────────────────

    fun setMediaItems(items: List<androidx.media3.common.MediaItem>, startIndex: Int = 0) {
        playbackConnection.setMediaItems(items, startIndex)
    }

    // ── State Mapping ────────────────────────────────────────────

    private fun mapToUiState(playerState: PlayerState, sleepRemainingMs: Long): PlayerUiState {
        val hasMedia = playerState.mediaItemCount > 0
        val trackProgress = if (playerState.duration > 0) {
            (playerState.currentPosition.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val playlistProgress = if (playerState.totalPlaylistDuration > 0) {
            (playerState.totalPlaylistPosition.toFloat() / playerState.totalPlaylistDuration.toFloat()).coerceIn(0f, 1f)
        } else 0f

        val trackRemaining = (playerState.duration - playerState.currentPosition).coerceAtLeast(0L)
        val playlistRemaining = (playerState.totalPlaylistDuration - playerState.totalPlaylistPosition).coerceAtLeast(0L)
        return PlayerUiState(
            isPlaying = playerState.isPlaying,
            isLoading = playerState.isLoading,
            title = playerState.title.ifEmpty { "No media loaded" },
            artist = playerState.artist,
            album = playerState.album,
            description = playerState.description,
            artworkUri = playerState.artworkUri,
            hasCoverArt = playerState.hasCoverArt,
            currentPosition = playerState.currentPosition,
            duration = playerState.duration,
            currentPositionFormatted = TimeFormatter.formatDuration(playerState.currentPosition),
            durationFormatted = TimeFormatter.formatDuration(playerState.duration),
            trackRemainingFormatted = "-" + TimeFormatter.formatDuration(trackRemaining),
            trackProgress = trackProgress,
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
            chapters = playerState.chapters,
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
