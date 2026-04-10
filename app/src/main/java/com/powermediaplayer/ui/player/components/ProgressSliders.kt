package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.*

/**
 * Dual progress sliders for track and playlist scrubbing.
 * Slider 1: Current track/chapter position
 * Slider 2: Total playlist/album/film position
 *
 * Custom touch interpolation prevents audio stuttering during rapid scrubbing
 * by debouncing seek operations.
 */
@Composable
fun ProgressSliders(
    // Track slider
    trackPosition: Float,
    trackPositionFormatted: String,
    trackDurationFormatted: String,
    trackSliderEnabled: Boolean,
    onTrackSeek: (Float) -> Unit,
    // Playlist slider
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
        // ── Track Progress Slider ────────────────────────────────
        Text(
            text = "Track",
            style = MaterialTheme.typography.labelSmall,
            color = TealAccent,
            modifier = Modifier.padding(start = 4.dp)
        )

        TrackSlider(
            position = trackPosition,
            positionFormatted = trackPositionFormatted,
            durationFormatted = trackDurationFormatted,
            enabled = trackSliderEnabled,
            onSeek = onTrackSeek
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Playlist Progress Slider ─────────────────────────────
        if (playlistSliderEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playlist",
                    style = MaterialTheme.typography.labelSmall,
                    color = Teal300,
                    modifier = Modifier.padding(start = 4.dp)
                )
                if (trackIndexDisplay.isNotEmpty()) {
                    Text(
                        text = trackIndexDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            TrackSlider(
                position = playlistPosition,
                positionFormatted = playlistPositionFormatted,
                durationFormatted = playlistDurationFormatted,
                enabled = playlistSliderEnabled,
                onSeek = onPlaylistSeek,
                trackColor = Teal300
            )
        }
    }
}

/**
 * Individual slider with time labels and custom teal styling.
 * Uses debounced seeking to prevent stuttering during rapid scrubbing.
 */
@Composable
private fun TrackSlider(
    position: Float,
    positionFormatted: String,
    durationFormatted: String,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    trackColor: androidx.compose.ui.graphics.Color = TealAccent,
    modifier: Modifier = Modifier
) {
    // Track user dragging state to prevent position updates overwriting drag
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
                // Debounced seek: only seek when user lifts finger
                onSeek(dragValue)
                isDragging = false
            },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = trackColor,
                activeTrackColor = trackColor,
                inactiveTrackColor = DisabledContent,
                disabledThumbColor = DisabledGrey,
                disabledActiveTrackColor = DisabledGrey,
                disabledInactiveTrackColor = DisabledContent
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
