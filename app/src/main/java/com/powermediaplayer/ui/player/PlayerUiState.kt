package com.powermediaplayer.ui.player

import android.net.Uri

/**
 * Complete UI state for the player screen.
 * All fields needed by Compose to render without additional computation.
 */
data class PlayerUiState(
    // Playback status
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,

    // Track info
    val title: String = "No media loaded",
    val artist: String = "",
    val album: String = "",
    val description: String = "",

    // Cover art
    val artworkUri: Uri? = null,
    val hasCoverArt: Boolean = false,

    // Progress - current track
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val currentPositionFormatted: String = "0:00",
    val durationFormatted: String = "0:00",
    val trackRemainingFormatted: String = "0:00",
    val trackProgress: Float = 0f, // 0.0 - 1.0

    // Progress - total playlist
    val totalPlaylistPosition: Long = 0L,
    val totalPlaylistDuration: Long = 0L,
    val playlistPositionFormatted: String = "0:00",
    val playlistDurationFormatted: String = "0:00",
    val playlistRemainingFormatted: String = "0:00",
    val playlistProgress: Float = 0f, // 0.0 - 1.0

    // Playback parameters
    val playbackSpeed: Float = 1.0f,
    val volume: Float = 1.0f,
    val brightness: Float = -1f, // -1 = system default

    // Sleep timer
    val sleepTimerRemainingMs: Long = 0L,
    val sleepTimerActive: Boolean = false,
    val sleepTimerFormatted: String = "",

    // Playlist info
    val currentTrackIndex: Int = 0,
    val totalTracks: Int = 0,
    val trackIndexDisplay: String = "", // "3 / 12"

    // Chapter support
    val chapters: List<com.powermediaplayer.service.ChapterInfo> = emptyList(),
    val currentChapterIndex: Int = -1,
    val hasChapters: Boolean = false,

    // Controls enabled state (for greying out)
    val controls: ControlsEnabledState = ControlsEnabledState(),

    // Video support
    val isVideoContent: Boolean = false
)

/**
 * Determines which controls should be enabled vs greyed out.
 * A control is disabled when the current media doesn't support that action.
 */
data class ControlsEnabledState(
    val previousTrack: Boolean = false,
    val nextTrack: Boolean = false,
    val previousChapter: Boolean = false,
    val nextChapter: Boolean = false,
    // File-boundary navigation — distinct from chapter nav (always file boundary,
    // ignores chapters). Greyed (not hidden) when not part of a multi-file queue.
    val previousFile: Boolean = false,
    val nextFile: Boolean = false,
    // Compound: chapter-aware navigation that falls back to file boundary
    val previousChapterOrTrack: Boolean = false,
    val nextChapterOrTrack: Boolean = false,
    val skipBack5: Boolean = true,
    val skipBack10: Boolean = true,
    val skipBack15: Boolean = true,
    val skipBack20: Boolean = true,
    val skipBack30: Boolean = true,
    val skipForward5: Boolean = true,
    val skipForward10: Boolean = true,
    val skipForward15: Boolean = true,
    val skipForward20: Boolean = true,
    val skipForward30: Boolean = true,
    val playPause: Boolean = false,
    val playbackSpeed: Boolean = true,
    val brightness: Boolean = true,
    val volume: Boolean = true,
    val sleepTimer: Boolean = true,
    val trackSlider: Boolean = false,
    val playlistSlider: Boolean = false
)
