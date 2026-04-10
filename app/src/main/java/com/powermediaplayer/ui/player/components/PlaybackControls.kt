package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powermediaplayer.ui.player.ControlsEnabledState
import com.powermediaplayer.ui.theme.DisabledGrey
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary

/**
 * Full row of all 13 playback transport controls displayed simultaneously.
 * Icons are bold vectors with thick lines. Disabled controls are greyed out.
 *
 * Layout: |◀◀| ⏪30 | ⏪20 | ⏪15 | ⏪10 | ⏪5 | ▶⏸ | 5⏩ | 10⏩ | 15⏩ | 20⏩ | 30⏩ | ▶▶|
 */
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    controls: ControlsEnabledState,
    onPreviousChapter: () -> Unit,
    onSkipBack: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: (Int) -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Wrap callbacks in remember to prevent unnecessary recompositions
    val skipBack30 = remember { { onSkipBack(30) } }
    val skipBack20 = remember { { onSkipBack(20) } }
    val skipBack15 = remember { { onSkipBack(15) } }
    val skipBack10 = remember { { onSkipBack(10) } }
    val skipBack5 = remember { { onSkipBack(5) } }
    val skipForward5 = remember { { onSkipForward(5) } }
    val skipForward10 = remember { { onSkipForward(10) } }
    val skipForward15 = remember { { onSkipForward(15) } }
    val skipForward20 = remember { { onSkipForward(20) } }
    val skipForward30 = remember { { onSkipForward(30) } }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous chapter/track
        item {
            ControlButton(
                icon = Icons.Filled.SkipPrevious,
                label = "Prev",
                enabled = controls.previousChapter || controls.previousTrack,
                onClick = onPreviousChapter,
                size = 36
            )
        }

        // Skip back buttons: 30, 20, 15, 10, 5
        item {
            SkipButton(
                seconds = 30,
                isForward = false,
                enabled = controls.skipBack30,
                onClick = skipBack30
            )
        }
        item {
            SkipButton(
                seconds = 20,
                isForward = false,
                enabled = controls.skipBack20,
                onClick = skipBack20
            )
        }
        item {
            SkipButton(
                seconds = 15,
                isForward = false,
                enabled = controls.skipBack15,
                onClick = skipBack15
            )
        }
        item {
            SkipButton(
                seconds = 10,
                isForward = false,
                enabled = controls.skipBack10,
                onClick = skipBack10
            )
        }
        item {
            SkipButton(
                seconds = 5,
                isForward = false,
                enabled = controls.skipBack5,
                onClick = skipBack5
            )
        }

        // Play/Pause — larger, central button
        item {
            IconButton(
                onClick = onPlayPause,
                enabled = controls.playPause,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(56.dp),
                    tint = if (controls.playPause) TealAccent else DisabledGrey
                )
            }
        }

        // Skip forward buttons: 5, 10, 15, 20, 30
        item {
            SkipButton(
                seconds = 5,
                isForward = true,
                enabled = controls.skipForward5,
                onClick = skipForward5
            )
        }
        item {
            SkipButton(
                seconds = 10,
                isForward = true,
                enabled = controls.skipForward10,
                onClick = skipForward10
            )
        }
        item {
            SkipButton(
                seconds = 15,
                isForward = true,
                enabled = controls.skipForward15,
                onClick = skipForward15
            )
        }
        item {
            SkipButton(
                seconds = 20,
                isForward = true,
                enabled = controls.skipForward20,
                onClick = skipForward20
            )
        }
        item {
            SkipButton(
                seconds = 30,
                isForward = true,
                enabled = controls.skipForward30,
                onClick = skipForward30
            )
        }

        // Next chapter/track
        item {
            ControlButton(
                icon = Icons.Filled.SkipNext,
                label = "Next",
                enabled = controls.nextChapter || controls.nextTrack,
                onClick = onNextChapter,
                size = 36
            )
        }
    }
}

/**
 * Skip button with bold icon and seconds label overlay.
 */
@Composable
private fun SkipButton(
    seconds: Int,
    isForward: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) TextPrimary else DisabledGrey

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                contentDescription = if (isForward) "Skip forward $seconds seconds" else "Skip back $seconds seconds",
                modifier = Modifier.size(32.dp),
                tint = tint
            )
            Text(
                text = "$seconds",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = tint,
                modifier = Modifier.offset(y = 14.dp)
            )
        }
    }
}

/**
 * Standard control button with icon.
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Int = 32
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(size.dp),
            tint = if (enabled) TextPrimary else DisabledGrey
        )
    }
}
