package com.powermediaplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary

/**
 * A compact colour picker dialog: a full-hue spectrum bar (tap or drag) for any colour, plus a
 * grid of common presets (whites, greys, black, and a vivid spectrum) for quick choices. No
 * dependency; returns the chosen colour on Apply.
 */
@Composable
fun ColourPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }

    val presets = remember {
        listOf(
            Color.White, Color(0xFFE0E0E0), Color(0xFF9E9E9E), Color(0xFF616161), Color(0xFF2B2B2B), Color(0xFF0E0E0E),
            Color(0xFFFF5252), Color(0xFFFF4081), Color(0xFFE040FB), Color(0xFF7C4DFF), Color(0xFF536DFE), Color(0xFF2979FF),
            Color(0xFF00B0FF), Color(0xFF00E5FF), Color(0xFF1DE9B6), Color(0xFF00E676), Color(0xFF76FF03), Color(0xFFC6FF00),
            Color(0xFFFFEA00), Color(0xFFFFC400), Color(0xFFFF9100), Color(0xFFFF6E40), TealAccent, Color(0xFFEA80FC),
        )
    }

    val hueBrush = remember {
        Brush.horizontalGradient(
            (0..360 step 30).map { Color.hsv(it.toFloat().coerceAtMost(359f), 1f, 1f) }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = SurfaceElevated) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("Player text colour", color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("Drag the spectrum or tap a swatch.", color = TextSecondary)
                Spacer(Modifier.height(14.dp))

                // Hue spectrum: tap or drag to set any vivid hue.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(hueBrush)
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val h = (pos.x / size.width).coerceIn(0f, 1f) * 359f
                                selected = Color.hsv(h, 1f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val h = (change.position.x / size.width).coerceIn(0f, 1f) * 359f
                                selected = Color.hsv(h, 1f, 1f)
                            }
                        }
                ) {}

                Spacer(Modifier.height(16.dp))

                // Preset grid, six per row.
                presets.chunked(6).forEach { rowColours ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        rowColours.forEach { c ->
                            val isSel = c.value == selected.value
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (isSel) 3.dp else 1.dp,
                                        color = if (isSel) TealAccent else Color(0x33FFFFFF),
                                        shape = CircleShape
                                    )
                                    .clickable { selected = c }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { onPick(selected) }) { Text("Apply", color = TealAccent) }
                }
            }
        }
    }
}
