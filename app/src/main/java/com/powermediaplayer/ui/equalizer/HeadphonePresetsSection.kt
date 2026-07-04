package com.powermediaplayer.ui.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.DisabledContent
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import com.powermediaplayer.util.BluetoothHelper
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * §C13 — bottom-of-EQ-tab "Headphone presets" section. Lists every
 * paired Bluetooth audio device; each row gets a dropdown to pick a
 * preset (or "Don't auto-switch"). The connect-time swap logic in
 * [EqualizerViewModel] reads the resulting per-address map and
 * applies the matching preset when that device becomes the audio
 * route.
 *
 * Uses [BluetoothHelper.bondedDevices] for the source-of-truth
 * device list. Falls back to "no paired devices" when the BT
 * permission is missing or BT is off.
 */
@Composable
fun HeadphonePresetsSection(
    viewModel: EqualizerViewModel
) {
    val context = LocalContext.current
    val bonded = remember { BluetoothHelper.bondedDevices(context).filter { it.isAudio } }
    val devicePresets by viewModel.devicePresets.collectAsStateWithLifecycle(initialValue = emptyMap())
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val presets = state.presets

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = DisabledContent)
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Headset, contentDescription = null, tint = TealAccent)
            Spacer(Modifier.width(8.dp))
            Text(
                "Headphone presets",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }
        Text(
            "Auto-applies a preset when a specific Bluetooth device " +
                "becomes the audio route. Pick \"Don't auto-switch\" to " +
                "leave that device alone.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        if (bonded.isEmpty()) {
            Text(
                "No paired Bluetooth audio devices. Pair one in system " +
                    "Settings → Bluetooth.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else {
            bonded.forEach { dev ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        dev.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    val current = devicePresets[dev.address] ?: -1L
                    val currentLabel = if (current == -1L) "Don't auto-switch"
                    else presets.firstOrNull { it.id == current }?.name ?: "Don't auto-switch"
                    PresetPicker(
                        currentLabel = currentLabel,
                        options = listOf<Pair<String, Long>>("Don't auto-switch" to -1L) +
                            presets.map { it.name to it.id },
                        onPick = { id ->
                            viewModel.setDevicePreset(dev.address, id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetPicker(
    currentLabel: String,
    options: List<Pair<String, Long>>,
    onPick: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(currentLabel, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (label, id) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onPick(id); expanded = false }
                )
            }
        }
    }
}
