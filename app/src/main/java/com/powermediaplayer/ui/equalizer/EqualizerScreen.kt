package com.powermediaplayer.ui.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.theme.*
import kotlin.math.roundToInt

/**
 * 10-band equalizer screen.
 *
 * Layout:
 *  1. Header with preset selector + Reset button
 *  2. Save / Delete preset buttons
 *  3. Frequency response curve (Canvas, interactive: clicking graph applies band values)
 *  4. Band level numeric input grid (replaces messy vertical sliders)
 *     Each band shows:  [freq label]  [-15..+15 dB text field]  [± nudge buttons]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isCasting by viewModel.isCasting.collectAsStateWithLifecycle()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .verticalScroll(rememberScrollState())
    ) {
        if (isCasting) {
            Surface(
                color = androidx.compose.ui.graphics.Color(0xFF1A2222),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "EQ applies to phone playback only — disabled while casting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.powermediaplayer.ui.theme.DisabledGrey,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        TopAppBar(
            title = {
                Text(
                    text = "Equalizer",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TealAccent
                )
            },
            actions = {
                IconButton(onClick = {
                    focusManager.clearFocus()
                    viewModel.resetToFlat()
                }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "Reset to flat", tint = TextSecondary)
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
                    value = uiState.selectedPresetName + if (uiState.isCustomModified) " (Modified)" else "",
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
                                        Text("Default", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                    }
                                }
                            },
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.selectPreset(preset)
                                presetExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // ── Save / Delete ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = { showSaveDialog = true },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Teal800, contentColor = TealAccent),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Preset")
            }
            val canDelete = uiState.selectedPresetId > 0 &&
                    uiState.presets.find { it.id == uiState.selectedPresetId }?.isDefault == false
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                enabled = canDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (canDelete) ErrorRed else DisabledGrey),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Frequency Response Curve ─────────────────────────────
        Text(
            text = "Frequency Response",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
        )
        FrequencyResponseCurve(
            bandLevels = uiState.bandLevels,
            minLevel = uiState.minLevel,
            maxLevel = uiState.maxLevel,
            onBandChange = { index, levelMillibels ->
                viewModel.setBandLevel(index, levelMillibels)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Band Numeric Input Grid ──────────────────────────────
        Text(
            text = "Band Levels  (dB)",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
        )

        // 5 columns × 2 rows of band inputs
        val bandCount = uiState.bandLevels.size
        val cols = 5
        val rows = (bandCount + cols - 1) / cols

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (row in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (col in 0 until cols) {
                        val idx = row * cols + col
                        if (idx < bandCount) {
                            BandInputCell(
                                frequency = uiState.bandFrequencies[idx],
                                levelMillibels = uiState.bandLevels[idx],
                                minLevel = uiState.minLevel,
                                maxLevel = uiState.maxLevel,
                                onLevelChange = { viewModel.setBandLevel(idx, it) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    // ── Save Preset Dialog ──────────────────────────────────────
    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                viewModel.savePreset(name)
                showSaveDialog = false
            }
        )
    }

    // ── Delete Preset Dialog ────────────────────────────────────
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
                }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = SurfaceElevated
        )
    }
}

/**
 * Band numeric input cell: ±1 nudge buttons + OutlinedTextField.
 * Done key on keyboard both applies and dismisses the keyboard.
 */
@Composable
private fun BandInputCell(
    frequency: String,
    levelMillibels: Int,
    minLevel: Int,
    maxLevel: Int,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val levelDb = levelMillibels / 100
    var textValue by remember(levelMillibels) { mutableStateOf(levelDb.toString()) }
    val minDb = minLevel / 100  // -15
    val maxDb = maxLevel / 100  // +15
    val focusManager = LocalFocusManager.current

    Surface(
        color = SurfaceElevated,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Text(
                text = frequency,
                style = MaterialTheme.typography.labelSmall,
                color = TealAccent,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            IconButton(
                onClick = {
                    val newDb = (levelDb + 1).coerceIn(minDb, maxDb)
                    onLevelChange(newDb * 100)
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "+1 dB", tint = TealAccent, modifier = Modifier.size(16.dp))
            }
            OutlinedTextField(
                value = textValue,
                onValueChange = { raw ->
                    textValue = raw
                    val parsed = raw.toIntOrNull()
                    if (parsed != null) {
                        onLevelChange(parsed.coerceIn(minDb, maxDb) * 100)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val parsed = textValue.toIntOrNull() ?: levelDb
                        val clamped = parsed.coerceIn(minDb, maxDb)
                        textValue = clamped.toString()
                        onLevelChange(clamped * 100)
                        // Dismiss keyboard and clear focus
                        defaultKeyboardAction(ImeAction.Done)
                        focusManager.clearFocus()
                    }
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = TealAccent,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                ),
                shape = MaterialTheme.shapes.small
            )
            IconButton(
                onClick = {
                    val newDb = (levelDb - 1).coerceIn(minDb, maxDb)
                    onLevelChange(newDb * 100)
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "-1 dB", tint = Teal300, modifier = Modifier.size(16.dp))
            }
            Text(text = "dB", style = MaterialTheme.typography.labelSmall, color = TextTertiary, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Interactive frequency response curve.
 * Drag anywhere on the curve to adjust the nearest band level.
 * The Y axis maps linearly from maxLevel (top) to minLevel (bottom).
 */
@Composable
private fun FrequencyResponseCurve(
    bandLevels: List<Int>,
    minLevel: Int,
    maxLevel: Int,
    onBandChange: (index: Int, newLevelMillibels: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val tealColor = TealAccent
    val teal300Color = Teal300
    val gridColor = DisabledContent

    var canvasWidth by remember { mutableFloatStateOf(0f) }
    var canvasHeight by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .onSizeChanged { size ->
                canvasWidth = size.width.toFloat()
                canvasHeight = size.height.toFloat()
            }
            .pointerInput(bandLevels.size, minLevel, maxLevel) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (canvasWidth <= 0f || canvasHeight <= 0f) return@detectDragGestures

                    val stepX = canvasWidth / (bandLevels.size - 1).coerceAtLeast(1)
                    // Find the nearest band index based on horizontal drag position
                    val bandIndex = (change.position.x / stepX)
                        .roundToInt()
                        .coerceIn(0, bandLevels.size - 1)

                    // Map Y position to level: top = maxLevel, bottom = minLevel
                    val range = (maxLevel - minLevel).toFloat()
                    val normalizedY = (change.position.y / canvasHeight).coerceIn(0f, 1f)
                    val newLevel = (maxLevel - normalizedY * range)
                        .toInt()
                        .coerceIn(minLevel, maxLevel)

                    onBandChange(bandIndex, newLevel)
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val range = (maxLevel - minLevel).toFloat()
        val zeroY = height * (maxLevel / range)

        // Zero line
        drawLine(
            color = gridColor,
            start = Offset(0f, zeroY),
            end = Offset(width, zeroY),
            strokeWidth = 1.dp.toPx()
        )

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
                    val prevX = (index - 1) * stepX
                    val prevLevel = bandLevels[index - 1]
                    val prevY = (maxLevel - prevLevel) / range * height
                    val cpX = (prevX + x) / 2
                    path.cubicTo(cpX, prevY, cpX, y, x, y)
                }
            }

            drawPath(path = path, color = tealColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

            // Dots at band positions — slightly larger to be more tappable
            bandLevels.forEachIndexed { index, level ->
                val x = index * stepX
                val normalizedLevel = (maxLevel - level) / range
                val y = normalizedLevel * height
                drawCircle(color = teal300Color, radius = 7.dp.toPx(), center = Offset(x, y))
                drawCircle(color = tealColor, radius = 4.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Preset", color = TealAccent) },
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
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Save", color = if (name.isNotBlank()) TealAccent else DisabledGrey)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated
    )
}
