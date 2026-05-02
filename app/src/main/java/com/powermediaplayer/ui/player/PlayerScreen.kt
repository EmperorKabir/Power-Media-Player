package com.powermediaplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.player.components.*
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.CoverArtColors

/**
 * Main player screen — fully adaptive layout.
 *
 * Compact (phone portrait): single column, controls at bottom
 * Medium (large phone / unfolded foldable in portrait): wider single column
 * Expanded (tablet / landscape foldable): two-panel — artwork left, controls right
 */
@Composable
fun PlayerScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var coverColors by remember { mutableStateOf<CoverArtColors?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Video ALWAYS uses the Compact layout regardless of screen size,
        // so the picture fills the whole screen on phones, tablets, and
        // unfolded foldables. Audio uses the size-appropriate layout.
        when {
            uiState.isVideoContent -> PlayerScreenCompact(
                uiState = uiState,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 0
            )
            windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded -> PlayerScreenExpanded(
                uiState = uiState,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true }
            )
            windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium -> PlayerScreenCompact(
                uiState = uiState,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 32
            )
            else -> PlayerScreenCompact(
                uiState = uiState,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 0
            )
        }

        // Cloud-fetch banner + error banner — top-level so they render
        // above whichever layout is active.
        if (uiState.cloudFetchInProgress) {
            Surface(
                color = Teal800.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = TealAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Loading chapters & metadata…",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        uiState.playerError?.let { errMsg ->
            Surface(
                color = ErrorRed.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errMsg,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextPrimary)
                    }
                }
            }
        }
    }

    // ── Sleep Timer Dialog ────────────────────────────────────────
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            isActive = uiState.sleepTimerActive,
            sleepTimerFormatted = uiState.sleepTimerFormatted,
            onDismiss = { showSleepTimerDialog = false },
            onSetTimer = { minutes ->
                viewModel.startSleepTimer(minutes)
                showSleepTimerDialog = false
            },
            onCancel = {
                viewModel.cancelSleepTimer()
                showSleepTimerDialog = false
            }
        )
    }

    // ── Chapter / Track Picker ────────────────────────────────────
    if (showChapterPicker) {
        ChapterPickerDialog(
            chapters = uiState.chapters,
            currentChapterIndex = uiState.currentChapterIndex,
            onChapterSelected = { index -> viewModel.seekToChapter(index) },
            playlist = emptyList(), // playlist surfaced from LibraryViewModel in future pass
            currentTrackIndex = uiState.currentTrackIndex,
            onTrackSelected = { index -> viewModel.seekToPlaylistPosition(index.toLong()) },
            onDismiss = { showChapterPicker = false }
        )
    }
}

// ── Compact Layout (Phone / Small Tablet) ─────────────────────────

@Composable
private fun PlayerScreenCompact(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    coverColors: CoverArtColors?,
    onColorsExtracted: (CoverArtColors?) -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit,
    horizontalPadding: Int = 0
) {
    // Video mode: tap to toggle controls; auto-hide after 32 s.
    // Audio: controls always visible — never auto-hide.
    // Keys deliberately exclude isPlaying so a brief buffering blip
    // doesn't reset the timer, which previously made it never fire.
    var controlsVisible by remember(uiState.isVideoContent) {
        mutableStateOf(true)
    }
    LaunchedEffect(uiState.isVideoContent, controlsVisible) {
        if (uiState.isVideoContent && controlsVisible) {
            delay(32_000)
            controlsVisible = false
        }
    }
    // Audio mode: ensure controls are visible at all times.
    LaunchedEffect(uiState.isVideoContent) {
        if (!uiState.isVideoContent) controlsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    // Tap toggles only in video mode; audio mode has no
                    // hide state to toggle.
                    if (uiState.isVideoContent) controlsVisible = !controlsVisible
                })
            }
    ) {
        if (uiState.isVideoContent) {
            // Video content: render the actual video frames
            // VideoSurface attaches directly to the ExoPlayer in PlaybackService
            VideoSurface(
                isVideoContent = true,
                videoWidth = uiState.videoWidth,
                videoHeight = uiState.videoHeight,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Audio content: show album art with palette-extracted colours
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = uiState.artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted
            )
        }

        // Scrim + control column animate together in video mode so the video
        // picture fully fills the screen between taps.
        val showOverlay = !uiState.isVideoContent || controlsVisible

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(animationSpec = tween(durationMillis = 500)),
            exit = fadeOut(animationSpec = tween(durationMillis = 1000))
        ) {
            // Gradient scrim — heavy fade for audio (album art under controls);
            // subtle bottom-only fade for video so the picture stays visible.
            val scrimColors = if (uiState.isVideoContent) {
                listOf(
                    Color.Transparent,
                    Color.Transparent,
                    OledBlack.copy(alpha = 0.5f),
                    OledBlack.copy(alpha = 0.9f),
                    OledBlack
                )
            } else {
                listOf(
                    Color.Transparent,
                    OledBlack.copy(alpha = 0.3f),
                    OledBlack.copy(alpha = 0.75f),
                    OledBlack.copy(alpha = 0.97f),
                    OledBlack
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(colors = scrimColors))
            )
        }

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(animationSpec = tween(durationMillis = 500)),
            exit = fadeOut(animationSpec = tween(durationMillis = 1000))
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 48.dp, start = horizontalPadding.dp, end = horizontalPadding.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TrackInfoSection(uiState, coverColors)
            Spacer(modifier = Modifier.height(12.dp))
            ChapterPickerChip(uiState, onShowChapterPicker)
            Spacer(modifier = Modifier.height(4.dp))
            ProgressSliders(
                trackPosition = uiState.trackProgress,
                trackPositionFormatted = uiState.currentPositionFormatted,
                trackDurationFormatted = uiState.durationFormatted,
                trackRemainingFormatted = uiState.trackRemainingFormatted,
                trackSliderEnabled = uiState.controls.trackSlider,
                onTrackSeek = { fraction ->
                    val target = uiState.chapterStartMs + (fraction * uiState.duration).toLong()
                    viewModel.seekTo(target)
                },
                playlistPosition = uiState.playlistProgress,
                playlistPositionFormatted = uiState.playlistPositionFormatted,
                playlistDurationFormatted = uiState.playlistDurationFormatted,
                playlistRemainingFormatted = uiState.playlistRemainingFormatted,
                playlistSliderEnabled = uiState.controls.playlistSlider,
                onPlaylistSeek = { fraction -> viewModel.seekToPlaylistPosition((fraction * uiState.totalPlaylistDuration).toLong()) },
                trackIndexDisplay = uiState.trackIndexDisplay
            )
            Spacer(modifier = Modifier.height(8.dp))
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                controls = uiState.controls,
                onPreviousFile = { viewModel.previousFile() },
                onPreviousChapterOrTrack = { viewModel.previousChapterOrTrack() },
                onSkipBack = { viewModel.skipBack(it) },
                onPlayPause = { viewModel.playPause() },
                onSkipForward = { viewModel.skipForward(it) },
                onNextChapterOrTrack = { viewModel.nextChapterOrTrack() },
                onNextFile = { viewModel.nextFile() }
            )
            Spacer(modifier = Modifier.height(4.dp))
            PreparedSpeedComponent(
                playbackSpeed = uiState.playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            TertiaryControls(
                currentVolume = viewModel.getCurrentVolume(),
                maxVolume = viewModel.getMaxVolume(),
                onVolumeChange = { viewModel.setVolume(it) },
                sleepTimerActive = uiState.sleepTimerActive,
                sleepTimerFormatted = uiState.sleepTimerFormatted,
                onSleepTimerClick = onShowSleepTimer
            )
            if (uiState.isLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(color = TealAccent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        } // close AnimatedVisibility

        // Top-right Cast button — visible only when controls are visible
        // (in video mode it auto-hides with everything else).
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)
        ) {
            CastButton(modifier = Modifier.size(40.dp))
        }

    }
}

// ── Expanded Layout (Tablet / Landscape Foldable) ─────────────────

@Composable
private fun PlayerScreenExpanded(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    coverColors: CoverArtColors?,
    onColorsExtracted: (CoverArtColors?) -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // Left panel: cover art only — video uses the Compact layout for
        // full-screen playback regardless of size class.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = uiState.artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted
            )
        }

        // Right panel: all controls
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TrackInfoSection(uiState, coverColors)
            Spacer(modifier = Modifier.height(12.dp))
            ChapterPickerChip(uiState, onShowChapterPicker)
            Spacer(modifier = Modifier.height(8.dp))
            ProgressSliders(
                trackPosition = uiState.trackProgress,
                trackPositionFormatted = uiState.currentPositionFormatted,
                trackDurationFormatted = uiState.durationFormatted,
                trackRemainingFormatted = uiState.trackRemainingFormatted,
                trackSliderEnabled = uiState.controls.trackSlider,
                onTrackSeek = { fraction ->
                    val target = uiState.chapterStartMs + (fraction * uiState.duration).toLong()
                    viewModel.seekTo(target)
                },
                playlistPosition = uiState.playlistProgress,
                playlistPositionFormatted = uiState.playlistPositionFormatted,
                playlistDurationFormatted = uiState.playlistDurationFormatted,
                playlistRemainingFormatted = uiState.playlistRemainingFormatted,
                playlistSliderEnabled = uiState.controls.playlistSlider,
                onPlaylistSeek = { fraction -> viewModel.seekToPlaylistPosition((fraction * uiState.totalPlaylistDuration).toLong()) },
                trackIndexDisplay = uiState.trackIndexDisplay
            )
            Spacer(modifier = Modifier.height(8.dp))
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                controls = uiState.controls,
                onPreviousFile = { viewModel.previousFile() },
                onPreviousChapterOrTrack = { viewModel.previousChapterOrTrack() },
                onSkipBack = { viewModel.skipBack(it) },
                onPlayPause = { viewModel.playPause() },
                onSkipForward = { viewModel.skipForward(it) },
                onNextChapterOrTrack = { viewModel.nextChapterOrTrack() },
                onNextFile = { viewModel.nextFile() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            PreparedSpeedComponent(
                playbackSpeed = uiState.playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            TertiaryControls(
                currentVolume = viewModel.getCurrentVolume(),
                maxVolume = viewModel.getMaxVolume(),
                onVolumeChange = { viewModel.setVolume(it) },
                sleepTimerActive = uiState.sleepTimerActive,
                sleepTimerFormatted = uiState.sleepTimerFormatted,
                onSleepTimerClick = onShowSleepTimer
            )
        }
    }
}

// ── Shared Sub-Composables ─────────────────────────────────────────

@Composable
private fun TrackInfoSection(uiState: PlayerUiState, coverColors: CoverArtColors?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = uiState.title,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (uiState.artist.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.artist,
                style = MaterialTheme.typography.titleMedium,
                color = coverColors?.vibrant ?: TealAccent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.album.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = uiState.album,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = uiState.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Tappable chip to open chapter/track picker.
 * Visible when chapters or playlist are available.
 */
@Composable
private fun ChapterPickerChip(uiState: PlayerUiState, onClick: () -> Unit) {
    val hasChapters = uiState.hasChapters
    val hasPlaylist = uiState.totalTracks > 1

    if (!hasChapters && !hasPlaylist) return

    val label = when {
        hasChapters -> {
            val idx = uiState.currentChapterIndex
            val chapter = uiState.chapters.getOrNull(idx)
            if (chapter != null) "Ch. ${idx + 1}: ${chapter.title}" else "Chapters"
        }
        else -> "${uiState.currentTrackIndex + 1} / ${uiState.totalTracks} tracks"
    }

    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (hasChapters) Icons.Filled.BookmarkBorder else Icons.Filled.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = SurfaceElevated,
            labelColor = TealAccent,
            leadingIconContentColor = TealAccent
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = TealAccent.copy(alpha = 0.4f)
        )
    )
}

// ── Sleep Timer Dialog ─────────────────────────────────────────────

@Composable
private fun SleepTimerDialog(
    isActive: Boolean,
    sleepTimerFormatted: String,
    onDismiss: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val presets = listOf(
        15 to "15 minutes",
        30 to "30 minutes",
        45 to "45 minutes",
        60 to "1 hour",
        90 to "1.5 hours",
        120 to "2 hours"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer", style = MaterialTheme.typography.titleLarge, color = TealAccent) },
        text = {
            Column {
                // Description
                Text(
                    text = "App will pause music after this much time:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Active timer countdown
                if (isActive) {
                    Surface(
                        color = Teal800,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Time remaining:",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = sleepTimerFormatted,
                                style = MaterialTheme.typography.titleMedium,
                                color = TealAccent
                            )
                        }
                    }
                }

                HorizontalDivider(color = DisabledContent, modifier = Modifier.padding(bottom = 4.dp))

                // Preset buttons
                presets.forEach { (minutes, label) ->
                    TextButton(
                        onClick = { onSetTimer(minutes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (isActive) {
                    HorizontalDivider(color = DisabledContent)
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel Timer",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ErrorRed,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) } },
        containerColor = SurfaceElevated
    )
}

