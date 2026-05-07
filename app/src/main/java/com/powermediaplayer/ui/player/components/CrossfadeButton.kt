package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MultipleStop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.settings.SettingsViewModel
import com.powermediaplayer.ui.theme.DisabledGrey
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary

/**
 * Crossfade button (Phase 4 / §B). Lives in the player transport row,
 * right of AudioEffectsButton, before Bluetooth. Tap opens the
 * crossfade settings sheet (9 toggles). Greyed when [enabled] is false
 * (Cast active / Spotify Connect / video media — caller decides).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossfadeButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    settingsVm: SettingsViewModel = hiltViewModel(),
    onFadeNow: () -> Unit = {}
) {
    val s by settingsVm.uiState.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    var lastInteract by remember { mutableStateOf(0L) }
    fun touch() { lastInteract = System.currentTimeMillis() }

    androidx.compose.runtime.LaunchedEffect(showSheet, lastInteract, s.crossfadePreFadeTriggerS) {
        // Reuse the same auto-hide pattern as audio/video effects sheets
        // — Settings → Auto-hide controls → "Sub-popup auto-hide
        // (crossfade panel)" not yet wired; default 5 s.
        if (showSheet) {
            kotlinx.coroutines.delay(5000)
            showSheet = false
        }
    }

    IconButton(
        onClick = { showSheet = true; touch() },
        enabled = enabled,
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.MultipleStop,
            contentDescription = if (enabled) "Crossfade settings" else "Crossfade disabled",
            tint = when {
                !enabled -> DisabledGrey
                s.crossfadeEnabled -> TealAccent
                else -> TealAccent.copy(alpha = 0.6f)
            }
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Crossfade",
                    style = MaterialTheme.typography.titleMedium,
                    color = TealAccent
                )
                // At-a-glance summary of the active config so users can
                // see at the top of the sheet whether the panel is on,
                // for how long, with which curve, and which behaviour
                // toggles are active.
                val curveLabel = when (s.crossfadeCurve) {
                    "EQUAL_POWER" -> "Equal-power"
                    "LINEAR" -> "Linear"
                    "EXPONENTIAL" -> "Exponential"
                    "S_CURVE" -> "S-curve"
                    else -> s.crossfadeCurve
                }
                val flagsActive = listOfNotNull(
                    if (s.crossfadeAlbumMode) "Album mode" else null,
                    if (s.crossfadeSkipSilence) "Skip silence" else null,
                    if (s.crossfadeFadeOutOnPause) "Fade pause" else null,
                    if (s.crossfadeFadeInOnResume) "Fade resume" else null
                ).joinToString(" · ").ifBlank { "no extras" }
                Text(
                    text = if (s.crossfadeEnabled)
                        "${s.crossfadeMs / 1000} s · $curveLabel · $flagsActive"
                    else "Off — toggle Master crossfade to enable",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.crossfadeEnabled) TextSecondary else TextTertiary
                )
                Spacer(Modifier.height(12.dp))

                ToggleRow(
                    label = "Master crossfade",
                    description = "Smooths the transition between two tracks instead of a hard cut.",
                    checked = s.crossfadeEnabled,
                    onChange = { settingsVm.setCrossfadeEnabled(it); touch() }
                )

                SliderRow(
                    label = "Crossfade duration",
                    description = "How long the smooth transition lasts. 0 = off, 5 s is typical.",
                    value = s.crossfadeMs / 1000f,
                    range = 0f..15f,
                    steps = 14,
                    suffix = "s",
                    onChange = { settingsVm.setCrossfadeMs((it * 1000).toInt()); touch() },
                    enabled = s.crossfadeEnabled
                )

                CurveDropdown(
                    current = s.crossfadeCurve,
                    enabled = s.crossfadeEnabled,
                    onChange = { settingsVm.setCrossfadeCurve(it); touch() }
                )

                ToggleRow(
                    label = "Album mode",
                    description = "Skip the crossfade between consecutive tracks of the same album so you keep the artist's intended gaps.",
                    checked = s.crossfadeAlbumMode,
                    onChange = { settingsVm.setCrossfadeAlbumMode(it); touch() },
                    enabled = s.crossfadeEnabled
                )

                ToggleRow(
                    label = "Skip silence",
                    description = "Trims leading and trailing silence from each track so the crossfade actually overlaps loud audio.",
                    checked = s.crossfadeSkipSilence,
                    onChange = { settingsVm.setCrossfadeSkipSilence(it); touch() },
                    enabled = s.crossfadeEnabled
                )

                SliderRow(
                    label = "Pre-fade trigger",
                    description = "When to start fading the current track out. Smaller = later, bigger = earlier.",
                    value = s.crossfadePreFadeTriggerS.toFloat(),
                    range = 1f..30f,
                    steps = 28,
                    suffix = "s",
                    onChange = { settingsVm.setCrossfadePreFadeTriggerS(it.toInt()); touch() },
                    enabled = s.crossfadeEnabled
                )

                ToggleRow(
                    label = "Manual fade-now",
                    description = "Enables a 'Fade now' button below — fast-skip with a 1.5 s fade rather than a hard cut.",
                    checked = s.crossfadeManualFadeNowEnabled,
                    onChange = { settingsVm.setCrossfadeManualFadeNowEnabled(it); touch() },
                    enabled = s.crossfadeEnabled
                )
                if (s.crossfadeManualFadeNowEnabled && s.crossfadeEnabled) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { onFadeNow(); touch() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("Fade now", color = TealAccent)
                    }
                }

                ToggleRow(
                    label = "Fade-out on pause",
                    description = "A short fade when you tap pause instead of an instant cut.",
                    checked = s.crossfadeFadeOutOnPause,
                    onChange = { settingsVm.setCrossfadeFadeOutOnPause(it); touch() }
                )

                ToggleRow(
                    label = "Fade-in on resume",
                    description = "A short fade-up when you tap play again.",
                    checked = s.crossfadeFadeInOnResume,
                    onChange = { settingsVm.setCrossfadeFadeInOnResume(it); touch() }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) TextPrimary else DisabledGrey)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = if (enabled) TextTertiary else DisabledGrey)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = TealAccent)
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    suffix: String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) TextPrimary else DisabledGrey,
                modifier = Modifier.weight(1f))
            Text("${value.toInt()} $suffix", style = MaterialTheme.typography.labelMedium,
                color = if (enabled) TealAccent else DisabledGrey)
        }
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = if (enabled) TextTertiary else DisabledGrey)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurveDropdown(
    current: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    val options = listOf(
        "EQUAL_POWER" to "Equal-power (DJ default)",
        "LINEAR" to "Linear",
        "EXPONENTIAL" to "Exponential",
        "S_CURVE" to "S-curve"
    )
    val selected = options.firstOrNull { it.first == current } ?: options.first()
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("Fade curve", style = MaterialTheme.typography.titleSmall,
            color = if (enabled) TextPrimary else DisabledGrey)
        Text(
            "Equal-power keeps perceived loudness constant — what real DJs use. " +
                "Linear is a constant-rate drop. Exponential lingers then drops fast. " +
                "S-curve eases in and out.",
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) TextTertiary else DisabledGrey
        )
        Spacer(Modifier.height(6.dp))
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { if (enabled) expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.second,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledGrey,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (token, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (token == current) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onChange(token)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
