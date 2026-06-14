package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    trackRemainingFormatted: String,
    trackSliderEnabled: Boolean,
    onTrackSeek: (Float) -> Unit,
    // Full / playlist slider
    playlistPosition: Float,
    playlistPositionFormatted: String,
    playlistDurationFormatted: String,
    playlistRemainingFormatted: String,
    playlistSliderEnabled: Boolean,
    onPlaylistSeek: (Float) -> Unit,
    // Track info
    trackIndexDisplay: String,
    // Live A-B loop markers (0..1 of the Track bar), null when not set.
    abStartFraction: Float? = null,
    abEndFraction: Float? = null,
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
            remainingFormatted = trackRemainingFormatted,
            enabled = trackSliderEnabled,
            onSeek = onTrackSeek,
            activeColor = TealAccent,
            abStartFraction = abStartFraction,
            abEndFraction = abEndFraction
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
            remainingFormatted = playlistRemainingFormatted,
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
    remainingFormatted: String,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    activeColor: androidx.compose.ui.graphics.Color = TealAccent,
    abStartFraction: Float? = null,
    abEndFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    // Reused across draws so the tick-rate redraw doesn't allocate.
    val abTextPaint = remember {
        android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val abTextSizePx = with(androidx.compose.ui.platform.LocalDensity.current) {
        9.sp.toPx()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
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
                // Tinted teal instead of plain grey so the unfilled
                // portion reads as part of the same control surface.
                inactiveTrackColor = if (enabled) activeColor.copy(alpha = 0.25f)
                    else activeColor.copy(alpha = 0.12f),
                disabledThumbColor = DisabledGrey,
                disabledActiveTrackColor = DisabledGrey.copy(alpha = 0.5f),
                disabledInactiveTrackColor = activeColor.copy(alpha = 0.12f)
            )
        )
        // ── Live A-B loop markers ────────────────────────────────
        // Drawn ON the track: a labelled tick at A and B, plus a tinted
        // band across the looped region. Colour is dynamic — amber while
        // only A is set (loop pending), teal once B is set (loop active).
        if (abStartFraction != null || abEndFraction != null) {
            val loopActive = abStartFraction != null && abEndFraction != null
            val pendingColor = androidx.compose.ui.graphics.Color(0xFFFFB300)
            val aColor = if (loopActive) activeColor else pendingColor
            androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                val thumb = 10.dp.toPx()
                val trackW = (size.width - 2 * thumb).coerceAtLeast(1f)
                val cy = size.height / 2f
                val topY = cy - 7.dp.toPx()
                val botY = cy + 9.dp.toPx()
                fun xFor(f: Float) = thumb + f.coerceIn(0f, 1f) * trackW
                if (loopActive) {
                    val xa = xFor(abStartFraction!!)
                    val xb = xFor(abEndFraction!!)
                    drawRect(
                        color = activeColor.copy(alpha = 0.28f),
                        topLeft = androidx.compose.ui.geometry.Offset(
                            minOf(xa, xb), cy - 3.dp.toPx()
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            kotlin.math.abs(xb - xa), 6.dp.toPx()
                        )
                    )
                }
                fun marker(f: Float, color: androidx.compose.ui.graphics.Color, label: String) {
                    val x = xFor(f)
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(x, topY),
                        end = androidx.compose.ui.geometry.Offset(x, botY),
                        strokeWidth = 2.5.dp.toPx()
                    )
                    drawCircle(
                        color = color, radius = 3.5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x, topY)
                    )
                    abTextPaint.color = color.toArgb()
                    abTextPaint.textSize = abTextSizePx
                    drawContext.canvas.nativeCanvas.drawText(
                        label, x, topY - 4.dp.toPx(), abTextPaint
                    )
                }
                abStartFraction?.let { marker(it, aColor, "A") }
                abEndFraction?.let { marker(it, activeColor, "B") }
            }
        }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = positionFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) TextSecondary else DisabledGrey
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = remainingFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) TextSecondary.copy(alpha = 0.75f) else DisabledGrey
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) TextSecondary else DisabledGrey
                )
            }
        }
    }
}
