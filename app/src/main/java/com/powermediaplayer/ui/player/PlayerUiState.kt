package com.powermediaplayer.ui.player

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * Complete UI state for the player screen.
 *
 * @Immutable now safe — the previously-included ByteArray field has
 * moved to a separate flow ([PlayerViewModel.artworkBytes]) so this
 * class contains only stable, value-type fields. With it stable,
 * Compose can skip downstream composables when only the position
 * ticks change, eliminating the per-tick recomposition that caused
 * video controls to stutter on large files.
 */
@Immutable
data class PlayerUiState(
    // Playback status
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    // vc31: true once the player holds ≥1 media item (incl. a paused
    // cold-start restore). Drives the empty-player guidance state.
    val hasMedia: Boolean = false,

    // Track info
    val title: String = "No media loaded",
    val artist: String = "",
    val album: String = "",
    val description: String = "",
    val year: Int = 0,
    val genre: String = "",

    // Cover art
    val artworkUri: Uri? = null,
    val hasCoverArt: Boolean = false,

    // Lyrics — populated for Spotify mirroring (LRCLib lookup).
    // Empty for local files unless a future lyrics file is wired in.
    val lyrics: String = "",
    // Synced lyrics for the highlight + tap-to-seek path. Each entry
    // is a (timeMs, text) pair from a parsed LRC file. Empty when only
    // plain lyrics are available.
    val syncedLyrics: List<com.powermediaplayer.cloud.LyricLine> = emptyList(),
    // True while an async lyric fetch is still in flight (Spotify). Drives the
    // single loading banner (lyrics are metadata) and the "No lyrics" panel
    // message, which shows only once loading has finished with an empty result.
    val lyricsLoading: Boolean = false,

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
    // I3 — the queue snapshot for the now-playing "Tracks" picker.
    val queue: List<com.powermediaplayer.service.QueueItemInfo> = emptyList(),

    // Chapter support
    val chapters: List<com.powermediaplayer.service.ChapterInfo> = emptyList(),
    val currentChapterIndex: Int = -1,
    val hasChapters: Boolean = false,
    // When inside a chapter, [chapterStartMs] / [chapterDurationMs] anchor the
    // track-slider to chapter-relative progress instead of full-file progress.
    val chapterStartMs: Long = 0L,
    val chapterDurationMs: Long = 0L,
    // Last playback error shown to the user.
    val playerError: String? = null,
    // Background cloud cache download in flight (chapters/metadata).
    val cloudFetchInProgress: Boolean = false,
    // Source video dimensions for aspect-ratio sizing (0 = unknown).
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,

    // Controls enabled state (for greying out)
    val controls: ControlsEnabledState = ControlsEnabledState(),

    // Video support
    val isVideoContent: Boolean = false,
    // Current item's media URI (mediaId) — lets surfaces like the mini-player fetch a video frame.
    val currentMediaUri: String = "",

    // Audio output indicator — codec · channel layout · sample rate.
    // Empty when no audio track is selected.
    val audioFormatLabel: String = "",

    // Content kind drives the dynamic label on Prev/Next buttons
    // (Track/Album/Chapter/Episode/etc.). Inferred in PlayerViewModel
    // from playerState fields; UNKNOWN falls back to File/Chapter.
    val mediaKind: MediaKind = MediaKind.UNKNOWN,

    // True when Spotify Connect is the active source (the local
    // ExoPlayer is silent; controls route to spotifyProvider).
    // Drives Cast button visibility — Cast doesn't apply to Spotify.
    val isSpotifyActive: Boolean = false,

    // True when audio/video is being sent to a Cast receiver
    // (CastPlayer is active, not the local ExoPlayer). Drives the
    // grey-out of audio/video effect controls that have no audible
    // / visible effect on the receiver.
    val isCasting: Boolean = false,

    // True when the current media item is in a format the default
    // Cast Media Receiver (CC1AD845) can play. False when the current
    // media is a video container the receiver cannot decode (MKV /
    // AVI / MOV / FLV / WMV / TS / 3GP). Used to grey out the Cast
    // button so the user doesn't tap, transfer, then watch the
    // receiver bail. Audio is always castable; only video formats
    // are gated.
    val isCurrentMediaCastable: Boolean = true
)

/**
 * Audit 3.3 — position-derived fields ride their own flow, collected
 * only by the slider section and the synced-lyrics panel. uiState
 * filters position-only ticks out of its emissions, so the rest of the
 * player tree recomposes on REAL state changes only.
 */
@Immutable
data class PositionUi(
    val trackProgress: Float = 0f,
    val positionFormatted: String = "0:00",
    val durationFormatted: String = "0:00",
    val remainingFormatted: String = "-0:00",
    val playlistProgress: Float = 0f,
    val playlistPositionFormatted: String = "0:00",
    val playlistDurationFormatted: String = "0:00",
    val playlistRemainingFormatted: String = "-0:00",
    val chapterStartMs: Long = 0L,
    val durationMs: Long = 0L,
    val totalPlaylistDurationMs: Long = 0L,
    val positionMs: Long = 0L
)

/**
 * Coarse content type used only for control labelling. Independent of
 * playback wiring — purely a display hint.
 */
enum class MediaKind {
    MUSIC,           // single audio track
    ALBUM,           // multi-track audio album
    AUDIOBOOK,       // long-form audio with chapters
    PODCAST,         // podcast episode
    SPOTIFY_TRACK,   // Spotify Connect mirror
    VIDEO,           // any video file
    UNKNOWN
}

/**
 * Determines which controls should be enabled vs greyed out.
 * A control is disabled when the current media doesn't support that action.
 */
@Immutable
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
