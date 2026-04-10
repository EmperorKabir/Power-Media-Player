package com.powermediaplayer.ui.player

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.player.components.*
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.CoverArtColors

/**
 * Main player screen displaying:
 * 1. Edge-to-edge cover art background (or OLED black)
 * 2. Track info overlay with gradient scrim
 * 3. Dual progress sliders (track + playlist)
 * 4. 13-button transport controls
 * 5. Speed, brightness, volume, sleep timer
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var coverColors by remember { mutableStateOf<CoverArtColors?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Apply extracted status bar color dynamically
    LaunchedEffect(coverColors) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val statusBarColor = coverColors?.statusBarColor ?: 0xFF000000.toInt()
        @Suppress("DEPRECATION")
        window.statusBarColor = statusBarColor
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Layer 1: Cover Art Background ────────────────────────
        CoverArtBackground(
            artworkUri = uiState.artworkUri,
            hasCoverArt = uiState.hasCoverArt,
            onColorsExtracted = { colors -> coverColors = colors }
        )

        // ── Layer 2: Gradient Scrim for readability ──────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            OledBlack.copy(alpha = 0.3f),
                            OledBlack.copy(alpha = 0.7f),
                            OledBlack.copy(alpha = 0.95f),
                            OledBlack
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        // ── Layer 3: Controls Overlay ────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            // Spacer pushes controls to bottom
            Spacer(modifier = Modifier.weight(1f))

            // ── Track Info ───────────────────────────────────────
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

            Spacer(modifier = Modifier.height(16.dp))

            // ── Progress Sliders ─────────────────────────────────
            ProgressSliders(
                trackPosition = uiState.trackProgress,
                trackPositionFormatted = uiState.currentPositionFormatted,
                trackDurationFormatted = uiState.durationFormatted,
                trackSliderEnabled = uiState.controls.trackSlider,
                onTrackSeek = { fraction ->
                    val seekPos = (fraction * uiState.duration).toLong()
                    viewModel.seekTo(seekPos)
                },
                playlistPosition = uiState.playlistProgress,
                playlistPositionFormatted = uiState.playlistPositionFormatted,
                playlistDurationFormatted = uiState.playlistDurationFormatted,
                playlistSliderEnabled = uiState.controls.playlistSlider,
                onPlaylistSeek = { fraction ->
                    val seekPos = (fraction * uiState.totalPlaylistDuration).toLong()
                    viewModel.seekToPlaylistPosition(seekPos)
                },
                trackIndexDisplay = uiState.trackIndexDisplay
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Primary Transport Controls (13 buttons) ──────────
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                controls = uiState.controls,
                onPreviousChapter = { viewModel.previousChapter() },
                onSkipBack = { seconds -> viewModel.skipBack(seconds) },
                onPlayPause = { viewModel.playPause() },
                onSkipForward = { seconds -> viewModel.skipForward(seconds) },
                onNextChapter = { viewModel.nextChapter() }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Secondary Controls (Speed, Brightness) ───────────
            SecondaryControls(
                playbackSpeed = uiState.playbackSpeed,
                onSpeedChange = { speed -> viewModel.setPlaybackSpeed(speed) },
                brightnessEnabled = uiState.controls.brightness
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Tertiary Controls (Volume, Sleep Timer) ──────────
            TertiaryControls(
                currentVolume = viewModel.getCurrentVolume(),
                maxVolume = viewModel.getMaxVolume(),
                onVolumeChange = { volume -> viewModel.setVolume(volume) },
                sleepTimerActive = uiState.sleepTimerActive,
                sleepTimerFormatted = uiState.sleepTimerFormatted,
                onSleepTimerClick = { showSleepTimerDialog = true }
            )

            // Loading indicator
            if (uiState.isLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(
                    color = TealAccent,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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
}

/**
 * Sleep timer selection dialog with preset times and cancel option.
 */
@Composable
private fun SleepTimerDialog(
    isActive: Boolean,
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
        title = {
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.titleLarge,
                color = TealAccent
            )
        },
        text = {
            Column {
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
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Cancel Timer",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ErrorRed,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        },
        containerColor = SurfaceElevated,
        titleContentColor = TealAccent,
        textContentColor = TextPrimary
    )
}
