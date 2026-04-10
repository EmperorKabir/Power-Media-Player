package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.*

/**
 * Dual progress sliders — always rendered, disabled when not applicable.
 *
 * Slider 1 (Track):    Current chapter/track position within the currently playing file.
 *                      Enabled when a file with known duration is loaded.
 *
 * Slider 2 (Full):     Position within the entire album/audiobook/playlist.
 *                      Enabled only when playing a multi-item queue.
 *                      Shown (greyed) always so the layout doesn't jump.
 *
 * Custom touch interpolation: seek is deferred until the user lifts their finger
 * to prevent audio stuttering during drag.
 */
@Composable
fun ProgressSliders(
    // Track slider
    trackPosition: Float,
    trackPositionFormatted: String,
    trackDurationFormatted: String,
    trackSliderEnabled: Boolean,
    onTrackSeek: (Float) -> Unit,
    // Full / playlist slider
    playlistPosition: Float,
    playlistPositionFormatted: String,
    playlistDurationFormatted: String,
    playlistSliderEnabled: Boolean,
    onPlaylistSeek: (Float) -> Unit,
    // Track info
    trackIndexDisplay: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // ── Track Slider — current file position ─────────────────
        Text(
            text = "Track",
            style = MaterialTheme.typography.labelSmall,
            color = if (trackSliderEnabled) TealAccent else DisabledGrey,
            modifier = Modifier.padding(start = 4.dp)
        )
        PositionSlider(
            position = trackPosition,
            positionFormatted = trackPositionFormatted,
            durationFormatted = trackDurationFormatted,
            enabled = trackSliderEnabled,
            onSeek = onTrackSeek,
            activeColor = TealAccent
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ── Full Slider — album/audiobook/playlist total ──────────
        // Always shown; disabled (greyed) when playing a single un-queued file.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Full",
                style = MaterialTheme.typography.labelSmall,
                color = if (playlistSliderEnabled) Teal300 else DisabledGrey,
                modifier = Modifier.padding(start = 4.dp)
            )
            if (trackIndexDisplay.isNotEmpty()) {
                Text(
                    text = trackIndexDisplay,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playlistSliderEnabled) TextSecondary else DisabledGrey,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
        PositionSlider(
            position = playlistPosition,
            positionFormatted = playlistPositionFormatted,
            durationFormatted = playlistDurationFormatted,
            enabled = playlistSliderEnabled,
            onSeek = onPlaylistSeek,
            activeColor = Teal300
        )
    }
}

/**
 * Single slider with labelled time positions.
 * Only fires [onSeek] when the user lifts their finger (onValueChangeFinished),
 * avoiding stuttering from rapid intermediate seeks.
 */
@Composable
private fun PositionSlider(
    position: Float,
    positionFormatted: String,
    durationFormatted: String,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    activeColor: androidx.compose.ui.graphics.Color = TealAccent,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = if (isDragging) dragValue else position,
            onValueChange = { value ->
                isDragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                onSeek(dragValue)
                isDragging = false
            },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor,
                inactiveTrackColor = if (enabled) DisabledContent else DisabledContent.copy(alpha = 0.4f),
                disabledThumbColor = DisabledGrey,
                disabledActiveTrackColor = DisabledGrey.copy(alpha = 0.5f),
                disabledInactiveTrackColor = DisabledContent.copy(alpha = 0.4f)
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = positionFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) TextSecondary else DisabledGrey
            )
            Text(
                text = durationFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) TextSecondary else DisabledGrey
            )
        }
    }
}
