package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
    // 2026-10-30: live scrub preview (fraction while dragging; end on release).
    onTrackScrub: ((Float) -> Unit)? = null,
    onTrackScrubEnd: ((seeked: Boolean) -> Unit)? = null,
    // Full / playlist slider
    playlistPosition: Float,
    playlistPositionFormatted: String,
    playlistDurationFormatted: String,
    playlistRemainingFormatted: String,
    playlistSliderEnabled: Boolean,
    onPlaylistSeek: (Float) -> Unit,
    onPlaylistScrub: ((Float) -> Unit)? = null,
    onPlaylistScrubEnd: ((seeked: Boolean) -> Unit)? = null,
    // #4 — tapping a time label opens the numeric-jump dialog (handled upward,
    // since this composable only knows fractions). Track + Full each get a pair.
    onTrackSeekToElapsedMs: (() -> Unit)? = null,
    onTrackSeekToRemainingMs: (() -> Unit)? = null,
    onPlaylistSeekToElapsedMs: (() -> Unit)? = null,
    onPlaylistSeekToRemainingMs: (() -> Unit)? = null,
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
            onScrub = onTrackScrub,
            onScrubEnd = onTrackScrubEnd,
            onSeekToElapsedMs = onTrackSeekToElapsedMs,
            onSeekToRemainingMs = onTrackSeekToRemainingMs,
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
            onScrub = onPlaylistScrub,
            onScrubEnd = onPlaylistScrubEnd,
            onSeekToElapsedMs = onPlaylistSeekToElapsedMs,
            onSeekToRemainingMs = onPlaylistSeekToRemainingMs,
            activeColor = Teal300
        )
    }
}

/**
 * Single slider with labelled time positions.
 * Only fires [onSeek] when the user lifts their finger, avoiding stuttering from rapid
 * intermediate seeks. 2026-10-30 (user video, issue 3): the drag is now handled by this
 * composable itself (the Material slider only draws): the thumb moves RELATIVE to the finger
 * with a gain that depends on the finger's speed — a quick sweep tracks the finger 1:1, a slow
 * deliberate drag moves the thumb a fraction of the finger distance (down to 5%), so slow =
 * precise. The drag fraction is reported through [onScrub] so the labels, a time bubble above
 * the thumb and the chapter chip follow the finger; [onScrubEnd] fires on release. A press
 * that never moves past the touch slop is still a tap-to-seek. See ScrubMath / ScrubMathTest.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PositionSlider(
    position: Float,
    positionFormatted: String,
    durationFormatted: String,
    remainingFormatted: String,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    onScrub: ((Float) -> Unit)? = null,
    onScrubEnd: ((seeked: Boolean) -> Unit)? = null,
    onSeekToElapsedMs: (() -> Unit)? = null,
    onSeekToRemainingMs: (() -> Unit)? = null,
    activeColor: androidx.compose.ui.graphics.Color = TealAccent,
    abStartFraction: Float? = null,
    abEndFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    // After the finger lifts, the audio seeks immediately but the incoming
    // [position] flow lags (the Spotify mirror only polls ~1 s), so reverting the
    // thumb to [position] on release made it SNAP BACK to the old spot for ~1 s
    // before jumping to the seeked spot. Hold the seeked value until the real
    // position catches up (either direction), with a safety window for a flow that
    // never lands exactly on target. Pure display — no seek/metadata behaviour change.
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    androidx.compose.runtime.LaunchedEffect(position, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (kotlin.math.abs(position - target) < 0.02f) pendingSeek = null
    }
    androidx.compose.runtime.LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            kotlinx.coroutines.delay(2000)
            pendingSeek = null
        }
    }
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

    // Precision-scrub gesture state (see the composable KDoc).
    var widthPx by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val touchSlopPx = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    // AU3 #4: the thumb is MEASURED (Material 3 1.3's default handle is 4 dp wide, not the
    // 20 dp disc the old constant assumed), so tap-to-seek and the bubble line up with the
    // track the Slider actually draws. Until the first layout, assume the 4 dp handle.
    val interactionSource = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    var thumbWidthPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val thumbHalfPx = if (thumbWidthPx > 0f) thumbWidthPx / 2f else with(density) { 2.dp.toPx() }
    // AU3 #10: RTL mirrors the bar (0 on the right).
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl
    val shownValue = if (isDragging) dragValue else (pendingSeek ?: position)
    // Latest display value for the gesture handler (a drag starts from what is on screen).
    val shownValueState = androidx.compose.runtime.rememberUpdatedState(shownValue)
    val onSeekState = androidx.compose.runtime.rememberUpdatedState(onSeek)
    val onScrubState = androidx.compose.runtime.rememberUpdatedState(onScrub)
    val onScrubEndState = androidx.compose.runtime.rememberUpdatedState(onScrubEnd)
    // Leaving composition mid-drag (tab switch from a notification, player layout swap):
    // release the preview so the labels and chip go back to live playback.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (isDragging) { isDragging = false; onScrubEndState.value?.invoke(false) } }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        // Initial pass + consume: the Material slider below never sees the
                        // touch, so it cannot snap the thumb under the finger.
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                        )
                        down.consume()
                        val startX = down.position.x
                        val startY = down.position.y
                        var lastX = startX
                        var lastT = down.uptimeMillis
                        var travelled = 0f
                        var velocityDpPerS = 0f
                        var moved = false
                        var abandoned = false
                        var completed = false   // set only when the gesture ends normally (release)
                        var value = shownValueState.value
                        val trackPx = (widthPx - 2f * thumbHalfPx).coerceAtLeast(1f)
                        // AU3 #3: whatever ends this gesture — release, a pointerInput restart
                        // when `enabled` flips mid-drag, or the slider leaving composition —
                        // the drag state and the view-model preview are released in `finally`.
                        try {
                            while (true) {
                                val event = awaitPointerEvent(
                                    androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                )
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) { change.consume(); break }
                                val dxRaw = change.position.x - lastX
                                val dx = if (rtl) -dxRaw else dxRaw
                                val dtMs = (change.uptimeMillis - lastT).coerceAtLeast(1L)
                                lastX = change.position.x
                                lastT = change.uptimeMillis
                                travelled += kotlin.math.abs(dxRaw)
                                if (!moved) {
                                    // AU3 #9: a vertical swipe that starts on the bar is a
                                    // scroll, not a seek — stop consuming and let the parent
                                    // scrollable take the (still unconsumed) moves.
                                    val dy = kotlin.math.abs(change.position.y - startY)
                                    if (dy > touchSlopPx && dy > travelled) { abandoned = true; break }
                                    if (ScrubMath.isTap(travelled, touchSlopPx)) {
                                        change.consume(); continue
                                    }
                                    moved = true
                                    isDragging = true
                                    dragValue = value
                                }
                                val sampleDpPerS = (kotlin.math.abs(dx) / density.density) / (dtMs / 1000f)
                                velocityDpPerS = ScrubMath.smoothVelocity(velocityDpPerS, sampleDpPerS)
                                value = ScrubMath.step(value, dx, trackPx, ScrubMath.gain(velocityDpPerS))
                                dragValue = value
                                onScrubState.value?.invoke(value)
                                change.consume()
                            }
                            if (abandoned) return@awaitEachGesture
                            if (!moved) {
                                // Tap: jump to the tapped point (the Material slider's old behaviour).
                                val raw = ScrubMath.fractionAt(startX, widthPx, thumbHalfPx)
                                val f = if (rtl) 1f - raw else raw
                                onSeekState.value(f)
                                pendingSeek = f
                            } else {
                                onSeekState.value(dragValue)
                                pendingSeek = dragValue
                            }
                            completed = true
                        } finally {
                            val wasDragging = isDragging
                            isDragging = false
                            // seeked = the drag completed normally (seek issued above); a
                            // cancelled drag (pointerInput restart / disposal) releases the
                            // preview without a seek.
                            if (wasDragging) onScrubEndState.value?.invoke(moved && !abandoned && completed)
                        }
                    }
                }
        ) {
        val sliderColors = SliderDefaults.colors(
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
        Slider(
            value = shownValue,
            // Touches are consumed above; this only serves accessibility actions
            // (TalkBack "adjust"), which still seek directly.
            onValueChange = { value -> if (!isDragging) { onSeek(value); pendingSeek = value } },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            colors = sliderColors,
            interactionSource = interactionSource,
            // The default thumb, measured: its real width drives tap-to-seek, the bubble and
            // the A-B markers (AU3 #4).
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = sliderColors,
                    enabled = enabled,
                    modifier = Modifier.onSizeChanged { thumbWidthPx = it.width.toFloat() }
                )
            }
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
                val thumb = thumbHalfPx
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
        // Time bubble above the thumb while dragging: the previewed time (the labels
        // below follow too, but the eye is on the bar).
        if (isDragging && widthPx > 0f) {
            Surface(
                color = activeColor,
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(0, -34.dp.roundToPx()) }
                    .graphicsLayer {
                        // State reads here run in the layer, not in composition (AU3 eff #4).
                        val trackPx = (widthPx - 2f * thumbHalfPx).coerceAtLeast(1f)
                        val f = dragValue.coerceIn(0f, 1f)
                        val x = thumbHalfPx + (if (rtl) 1f - f else f) * trackPx
                        translationX = x - size.width / 2f
                    }
            ) {
                Text(
                    text = positionFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.Black,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
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
                color = if (enabled) TextSecondary else DisabledGrey,
                // #4 — tap elapsed → numeric jump dialog; 48dp touch target.
                modifier = if (enabled && onSeekToElapsedMs != null) {
                    Modifier
                        .clickable { onSeekToElapsedMs() }
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 4.dp)
                } else Modifier
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = remainingFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) TextSecondary.copy(alpha = 0.75f) else DisabledGrey,
                    // #4 — tap remaining → "jump so this much remains" dialog.
                    modifier = if (enabled && onSeekToRemainingMs != null) {
                        Modifier
                            .clickable { onSeekToRemainingMs() }
                            .heightIn(min = 48.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = 4.dp)
                    } else Modifier
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
