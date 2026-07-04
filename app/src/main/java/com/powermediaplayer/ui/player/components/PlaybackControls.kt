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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
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
    onPreviousFile: () -> Unit,
    onPreviousChapterOrTrack: () -> Unit,
    onSkipBack: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: (Int) -> Unit,
    onNextChapterOrTrack: () -> Unit,
    onNextFile: () -> Unit,
    modifier: Modifier = Modifier,
    mediaKind: com.powermediaplayer.ui.player.MediaKind = com.powermediaplayer.ui.player.MediaKind.UNKNOWN
) {
    // Label mapping per content type:
    //   MUSIC          → File="Track",  Chapter="Track"
    //   ALBUM          → File="Album",  Chapter="Track"
    //   AUDIOBOOK      → File="Book",   Chapter="Chapter"
    //   PODCAST        → File="Show",   Chapter="Episode"
    //   SPOTIFY_TRACK  → File="Track",  Chapter="Track"
    //   VIDEO          → File="Video",  Chapter="Chapter"
    //   UNKNOWN        → File / Chapter (legacy fallback)
    val (fileLabel, chapterLabel) = when (mediaKind) {
        com.powermediaplayer.ui.player.MediaKind.MUSIC -> "Track" to "Track"
        com.powermediaplayer.ui.player.MediaKind.ALBUM -> "Album" to "Track"
        com.powermediaplayer.ui.player.MediaKind.AUDIOBOOK -> "Book" to "Chapter"
        com.powermediaplayer.ui.player.MediaKind.PODCAST -> "Show" to "Episode"
        com.powermediaplayer.ui.player.MediaKind.SPOTIFY_TRACK -> "Track" to "Track"
        com.powermediaplayer.ui.player.MediaKind.VIDEO -> "Video" to "Chapter"
        com.powermediaplayer.ui.player.MediaKind.UNKNOWN -> "File" to "Chapter"
    }
    // Collapse: when file/chapter labels resolve to the same word
    // (e.g. MUSIC → both = "Track"), hide the chapter pair so the
    // user only sees one back-track and one forward-track button.
    val collapseChapterPair = fileLabel.equals(chapterLabel, ignoreCase = true)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Row 1: Primary Controls ──────────────────────────────
        // Five buttons: |◀◀ file| |◀ ch| ▶/⏸ |ch ▶| |file ▶▶|
        // File buttons greyed (not hidden) when not in a multi-file queue.
        // Audit 6.9 - five fixed-size buttons need >=344dp; on 320dp-class
        // windows (small phones, split-screen halves, narrow desktop
        // windows) the outer File buttons clipped off both edges. Scale
        // the whole row down when the window can't fit it; touch targets
        // bottom out at ~44dp via the 0.82 floor.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val rowScale = (maxWidth / 360.dp).coerceIn(0.82f, 1f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .graphicsLayer {
                    scaleX = rowScale
                    scaleY = rowScale
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrevFileButton(
                enabled = controls.previousFile,
                onClick = onPreviousFile,
                label = fileLabel
            )

            if (!collapseChapterPair) {
                PrevChapterTrackButton(
                    enabled = controls.previousChapterOrTrack,
                    onClick = onPreviousChapterOrTrack,
                    label = chapterLabel
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

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

            Spacer(modifier = Modifier.width(8.dp))

            if (!collapseChapterPair) {
                NextChapterTrackButton(
                    enabled = controls.nextChapterOrTrack,
                    onClick = onNextChapterOrTrack,
                    label = chapterLabel
                )
            }

            NextFileButton(
                enabled = controls.nextFile,
                onClick = onNextFile,
                label = fileLabel
            )
        }
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

            Spacer(modifier = Modifier.width(8.dp))

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
 * Skip button — double-arrow icon with number+"s" overlaid in the centre.
 *
 * The label is drawn TWICE, creating an outline effect:
 *  1. Stroke pass (black/dark, Stroke drawStyle) — forms the contrasting outline
 *  2. Fill pass (TealAccent) on top — the actual visible text
 * This ensures the label is readable against both the arrows and any background.
 */
@Composable
private fun SkipButton(
    seconds: Int,
    isForward: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val wrappedClick: () -> Unit = {
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "SkipBtn click fwd=$isForward sec=$seconds enabled=$enabled")
        onClick()
    }
    val iconTint  = if (enabled) TextPrimary.copy(alpha = 0.5f) else DisabledGrey.copy(alpha = 0.35f)
    val fillColor = if (enabled) TealAccent else DisabledGrey
    val outlineColor = Color.Black

    // 20% bump from prior 15/11 sp so 5s/10s/30s read at glance even on
    // an unfolded fold. Now sourced from MaterialTheme.typography so
    // the user's Font-size scale propagates here too.
    val numStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
    val sStyle   = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold)

    IconButton(
        onClick = wrappedClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp)
        ) {
            // Arrow icon — dimmed so the overlay label reads through
            Icon(
                imageVector = if (isForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                contentDescription = if (isForward) "Skip forward $seconds s" else "Skip back $seconds s",
                modifier = Modifier.size(38.dp),
                tint = iconTint
            )

            // Number + "s" label overlaid centred on the arrows
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                // ── Number ──────────────────────────────────────
                Box {
                    // Outline pass
                    Text(
                        text = "$seconds",
                        style = numStyle.copy(
                            drawStyle = Stroke(width = 5f, join = StrokeJoin.Round)
                        ),
                        color = outlineColor
                    )
                    // Fill pass
                    Text(
                        text = "$seconds",
                        style = numStyle,
                        color = fillColor
                    )
                }

                // ── "s" suffix ───────────────────────────────────
                Box(modifier = Modifier.padding(start = 1.dp, bottom = 1.dp)) {
                    // Outline pass
                    Text(
                        text = "s",
                        style = sStyle.copy(
                            drawStyle = Stroke(width = 4f, join = StrokeJoin.Round)
                        ),
                        color = outlineColor
                    )
                    // Fill pass
                    Text(
                        text = "s",
                        style = sStyle,
                        color = fillColor.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

/**
 * ── Prepared UI Components for Coordinator Agent ──────────────────────
 * Labeled navigation buttons to explicitly distinguish between
 * structural skips like 'Chapter / Track' vs entire 'File'.
 */

@Composable
fun LabelledNavigationButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 32
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(64.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(size.dp),
                tint = if (enabled) TextPrimary else DisabledGrey
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) TextPrimary else DisabledGrey
            )
        }
    }
}

@Composable
fun NextChapterTrackButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Chapter"
) {
    LabelledNavigationButton(
        icon = Icons.Filled.SkipNext,
        label = label,
        contentDescription = "Next $label",
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun PrevChapterTrackButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Chapter"
) {
    LabelledNavigationButton(
        icon = Icons.Filled.SkipPrevious,
        label = label,
        contentDescription = "Previous $label",
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun NextFileButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "File"
) {
    LabelledNavigationButton(
        icon = Icons.Filled.SkipNext,
        label = label,
        contentDescription = "Next $label",
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun PrevFileButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "File"
) {
    LabelledNavigationButton(
        icon = Icons.Filled.SkipPrevious,
        label = label,
        contentDescription = "Previous $label",
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}
