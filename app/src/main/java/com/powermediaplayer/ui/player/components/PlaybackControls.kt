package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powermediaplayer.ui.player.ControlsEnabledState
import com.powermediaplayer.ui.theme.DisabledGrey
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary

/**
 * Full set of 13 playback transport controls, divided across two rows
 * to guarantee visibility on all screen sizes (phones, tablets, foldables).
 *
 * Row 1 (Primary):  |◀◀|    ▶/⏸    |▶▶|   — always centered & fully visible
 * Row 2 (Skip): |⏪30 ⏪20 ⏪15 ⏪10 ⏪5|     |5⏩ 10⏩ 15⏩ 20⏩ 30⏩|  — horizontally scrollable
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
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Row 1: Primary Controls ──────────────────────────────
        // Always fully visible — no scrolling needed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous chapter / track
            ControlButton(
                icon = Icons.Filled.SkipPrevious,
                label = "Previous",
                enabled = controls.previousChapter || controls.previousTrack,
                onClick = onPreviousChapter,
                size = 32
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Play / Pause — large central button
            IconButton(
                onClick = onPlayPause,
                enabled = controls.playPause,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(64.dp),
                    tint = if (controls.playPause) TealAccent else DisabledGrey
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Next chapter / track
            ControlButton(
                icon = Icons.Filled.SkipNext,
                label = "Next",
                enabled = controls.nextChapter || controls.nextTrack,
                onClick = onNextChapter,
                size = 32
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Row 2: Skip Buttons ──────────────────────────────────
        // Horizontally scrollable so all 10 skip buttons are reachable
        // on any screen width without being cut off
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(8.dp))

            // Skip back: 30, 20, 15, 10, 5
            SkipButton(seconds = 30, isForward = false, enabled = controls.skipBack30, onClick = { onSkipBack(30) })
            SkipButton(seconds = 20, isForward = false, enabled = controls.skipBack20, onClick = { onSkipBack(20) })
            SkipButton(seconds = 15, isForward = false, enabled = controls.skipBack15, onClick = { onSkipBack(15) })
            SkipButton(seconds = 10, isForward = false, enabled = controls.skipBack10, onClick = { onSkipBack(10) })
            SkipButton(seconds = 5,  isForward = false, enabled = controls.skipBack5,  onClick = { onSkipBack(5) })

            Spacer(modifier = Modifier.width(20.dp))

            // Skip forward: 5, 10, 15, 20, 30
            SkipButton(seconds = 5,  isForward = true, enabled = controls.skipForward5,  onClick = { onSkipForward(5) })
            SkipButton(seconds = 10, isForward = true, enabled = controls.skipForward10, onClick = { onSkipForward(10) })
            SkipButton(seconds = 15, isForward = true, enabled = controls.skipForward15, onClick = { onSkipForward(15) })
            SkipButton(seconds = 20, isForward = true, enabled = controls.skipForward20, onClick = { onSkipForward(20) })
            SkipButton(seconds = 30, isForward = true, enabled = controls.skipForward30, onClick = { onSkipForward(30) })

            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

/**
 * Skip button: the double-arrow icon is rendered semi-transparent, then the
 * number + "s" label is overlaid CENTRED on top of it — matching the sketch
 * where the text clearly reads through the arrows.
 */
@Composable
private fun SkipButton(
    seconds: Int,
    isForward: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val iconTint  = if (enabled) TextPrimary.copy(alpha = 0.55f) else DisabledGrey.copy(alpha = 0.4f)
    val labelTint = if (enabled) TealAccent else DisabledGrey

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp)
        ) {
            // Double-arrow icon — slightly transparent so the overlay label reads clearly
            Icon(
                imageVector = if (isForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                contentDescription = if (isForward) "Skip forward $seconds s" else "Skip back $seconds s",
                modifier = Modifier.size(38.dp),
                tint = iconTint
            )

            // Number + "s" overlaid in the centre of the arrows
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$seconds",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = labelTint
                )
                Text(
                    text = "s",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = labelTint.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 1.dp, bottom = 1.dp)
                )
            }
        }
    }
}

/**
 * Generic icon control button (SkipPrevious / SkipNext).
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
        modifier = Modifier.size(52.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(size.dp),
            tint = if (enabled) TextPrimary else DisabledGrey
        )
    }
}
