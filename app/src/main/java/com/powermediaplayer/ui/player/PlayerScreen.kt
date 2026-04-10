package com.powermediaplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> {
            // Landscape tablet / unfolded foldable in landscape: side-by-side
            PlayerScreenExpanded(
                uiState = uiState,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true }
            )
        }
        WindowWidthSizeClass.Medium -> {
            // Large phone / foldable in portrait: wider stacked layout
            PlayerScreenCompact(
                uiState = uiState,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 32 // Extra padding to use the extra width
            )
        }
        else -> {
            // Compact — standard phone
            PlayerScreenCompact(
                uiState = uiState,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 0
            )
        }
    }

    // ── Sleep Timer Dialog ────────────────────────────────────────
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            isActive = uiState.sleepTimerActive,
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
    Box(modifier = Modifier.fillMaxSize()) {
        CoverArtBackground(
            artworkUri = uiState.artworkUri,
            hasCoverArt = uiState.hasCoverArt,
            onColorsExtracted = onColorsExtracted
        )

        // Gradient scrim for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            OledBlack.copy(alpha = 0.3f),
                            OledBlack.copy(alpha = 0.75f),
                            OledBlack.copy(alpha = 0.97f),
                            OledBlack
                        )
                    )
                )
        )

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
                trackSliderEnabled = uiState.controls.trackSlider,
                onTrackSeek = { fraction -> viewModel.seekTo((fraction * uiState.duration).toLong()) },
                playlistPosition = uiState.playlistProgress,
                playlistPositionFormatted = uiState.playlistPositionFormatted,
                playlistDurationFormatted = uiState.playlistDurationFormatted,
                playlistSliderEnabled = uiState.controls.playlistSlider,
                onPlaylistSeek = { fraction -> viewModel.seekToPlaylistPosition((fraction * uiState.totalPlaylistDuration).toLong()) },
                trackIndexDisplay = uiState.trackIndexDisplay
            )
            Spacer(modifier = Modifier.height(8.dp))
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                controls = uiState.controls,
                onPreviousChapter = { viewModel.previousChapter() },
                onSkipBack = { viewModel.skipBack(it) },
                onPlayPause = { viewModel.playPause() },
                onSkipForward = { viewModel.skipForward(it) },
                onNextChapter = { viewModel.nextChapter() }
            )
            Spacer(modifier = Modifier.height(4.dp))
            SecondaryControls(
                playbackSpeed = uiState.playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                brightnessEnabled = uiState.controls.brightness
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
        // Left panel: cover art fills half the screen
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted
            )
            // Subtle right-edge fade into black
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, OledBlack),
                            startX = Float.POSITIVE_INFINITY,
                            endX = 0f
                        )
                    )
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
                trackSliderEnabled = uiState.controls.trackSlider,
                onTrackSeek = { fraction -> viewModel.seekTo((fraction * uiState.duration).toLong()) },
                playlistPosition = uiState.playlistProgress,
                playlistPositionFormatted = uiState.playlistPositionFormatted,
                playlistDurationFormatted = uiState.playlistDurationFormatted,
                playlistSliderEnabled = uiState.controls.playlistSlider,
                onPlaylistSeek = { fraction -> viewModel.seekToPlaylistPosition((fraction * uiState.totalPlaylistDuration).toLong()) },
                trackIndexDisplay = uiState.trackIndexDisplay
            )
            Spacer(modifier = Modifier.height(8.dp))
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                controls = uiState.controls,
                onPreviousChapter = { viewModel.previousChapter() },
                onSkipBack = { viewModel.skipBack(it) },
                onPlayPause = { viewModel.playPause() },
                onSkipForward = { viewModel.skipForward(it) },
                onNextChapter = { viewModel.nextChapter() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryControls(
                playbackSpeed = uiState.playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                brightnessEnabled = uiState.controls.brightness
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
    onDismiss: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val presets = listOf(15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 90 to "1.5 hours", 120 to "2 hours")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer", style = MaterialTheme.typography.titleLarge, color = TealAccent) },
        text = {
            Column {
                presets.forEach { (minutes, label) ->
                    TextButton(onClick = { onSetTimer(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (isActive) {
                    HorizontalDivider(color = DisabledContent)
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel Timer", style = MaterialTheme.typography.bodyLarge, color = ErrorRed, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) } },
        containerColor = SurfaceElevated
    )
}
