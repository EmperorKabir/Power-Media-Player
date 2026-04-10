package com.powermediaplayer.ui.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.theme.*

/**
 * 10-band equalizer screen with:
 * - Visual frequency response curve
 * - 10 vertical band sliders (31Hz - 16kHz)
 * - Preset selector (defaults + user presets)
 * - Save/delete preset functionality
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Equalizer",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TealAccent
                )
            },
            actions = {
                // Reset button
                IconButton(onClick = { viewModel.resetToFlat() }) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset to flat",
                        tint = TextSecondary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = OledBlack)
        )

        // ── Preset Selector ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Preset:",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                modifier = Modifier.padding(end = 12.dp)
            )

            ExposedDropdownMenuBox(
                expanded = presetExpanded,
                onExpandedChange = { presetExpanded = !presetExpanded },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = uiState.selectedPresetName +
                            if (uiState.isCustomModified) " (Modified)" else "",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TealAccent,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = DisabledGrey,
                        cursorColor = TealAccent
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                ExposedDropdownMenu(
                    expanded = presetExpanded,
                    onDismissRequest = { presetExpanded = false },
                    containerColor = SurfaceElevated
                ) {
                    uiState.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = preset.name,
                                        color = if (preset.id == uiState.selectedPresetId) TealAccent else TextPrimary
                                    )
                                    if (preset.isDefault) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Default",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextTertiary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                viewModel.selectPreset(preset)
                                presetExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // ── Save / Delete buttons ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = { showSaveDialog = true },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Teal800,
                    contentColor = TealAccent
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Preset")
            }

            // Only show delete for user (non-default) presets
            val canDelete = uiState.selectedPresetId > 0 &&
                    uiState.presets.find { it.id == uiState.selectedPresetId }?.isDefault == false

            OutlinedButton(
                onClick = { showDeleteDialog = true },
                enabled = canDelete,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (canDelete) ErrorRed else DisabledGrey
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Frequency Response Curve ─────────────────────────────
        FrequencyResponseCurve(
            bandLevels = uiState.bandLevels,
            minLevel = uiState.minLevel,
            maxLevel = uiState.maxLevel,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── 10 Band Sliders ──────────────────────────────────────
        Text(
            text = "Band Adjustment",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
        )

        // dB labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("+15dB", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            Text("0dB", style = MaterialTheme.typography.labelSmall, color = TealAccent)
            Text("-15dB", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }

        // Band sliders in a row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            uiState.bandFrequencies.forEachIndexed { index, freq ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    // Vertical slider (rotated)
                    Slider(
                        value = uiState.bandLevels[index].toFloat(),
                        onValueChange = { viewModel.setBandLevel(index, it.toInt()) },
                        valueRange = uiState.minLevel.toFloat()..uiState.maxLevel.toFloat(),
                        modifier = Modifier
                            .height(160.dp)
                            .width(32.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = TealAccent,
                            activeTrackColor = TealAccent,
                            inactiveTrackColor = DisabledContent
                        )
                    )

                    // Frequency label
                    Text(
                        text = freq,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    // Level value
                    Text(
                        text = "${uiState.bandLevels[index] / 100}dB",
                        style = MaterialTheme.typography.labelSmall,
                        color = TealAccent
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    // ── Save Preset Dialog ───────────────────────────────────────
    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                viewModel.savePreset(name)
                showSaveDialog = false
            }
        )
    }

    // ── Delete Preset Dialog ─────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Preset", color = ErrorRed) },
            text = {
                Text(
                    "Delete \"${uiState.selectedPresetName}\"? This cannot be undone.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePreset(uiState.selectedPresetId)
                    showDeleteDialog = false
                }) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceElevated
        )
    }
}

/**
 * Visual frequency response curve drawn with Canvas.
 */
@Composable
private fun FrequencyResponseCurve(
    bandLevels: List<Int>,
    minLevel: Int,
    maxLevel: Int,
    modifier: Modifier = Modifier
) {
    val tealColor = TealAccent
    val gridColor = DisabledContent

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val range = (maxLevel - minLevel).toFloat()

        // Draw zero line
        val zeroY = height * (maxLevel / range)
        drawLine(
            color = gridColor,
            start = Offset(0f, zeroY),
            end = Offset(width, zeroY),
            strokeWidth = 1.dp.toPx()
        )

        // Draw frequency response path
        if (bandLevels.isNotEmpty()) {
            val path = Path()
            val stepX = width / (bandLevels.size - 1).coerceAtLeast(1)

            bandLevels.forEachIndexed { index, level ->
                val x = index * stepX
                val normalizedLevel = (maxLevel - level) / range
                val y = normalizedLevel * height

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    // Smooth curve using cubic bezier
                    val prevX = (index - 1) * stepX
                    val prevLevel = bandLevels[index - 1]
                    val prevY = (maxLevel - prevLevel) / range * height
                    val cpX = (prevX + x) / 2

                    path.cubicTo(cpX, prevY, cpX, y, x, y)
                }
            }

            drawPath(
                path = path,
                color = tealColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )

            // Draw dots at each band position
            bandLevels.forEachIndexed { index, level ->
                val x = index * stepX
                val normalizedLevel = (maxLevel - level) / range
                val y = normalizedLevel * height

                drawCircle(
                    color = tealColor,
                    radius = 5.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
    }
}

/**
 * Dialog for naming and saving a custom EQ preset.
 */
@Composable
private fun SavePresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Save Preset", color = TealAccent)
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset name") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledGrey,
                    cursorColor = TealAccent,
                    focusedLabelColor = TealAccent,
                    unfocusedLabelColor = TextSecondary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("Save", color = if (name.isNotBlank()) TealAccent else DisabledGrey)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = SurfaceElevated
    )
}
