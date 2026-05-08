package com.powermediaplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.powermediaplayer.ui.equalizer.EqualizerViewModel
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary

/**
 * §C13 — settings entry for Headphone-aware EQ. Opens a chooser sheet
 * listing every saved Equalizer preset; selection persists to the
 * `headphoneEqPresetId` DataStore key, after which
 * [EqualizerViewModel] auto-swaps to / restores from the preset on
 * headphone connect / disconnect events emitted by
 * [com.powermediaplayer.audio.AudioOutputDetector].
 */
@Composable
fun HeadphoneEqSection(
    eqVm: EqualizerViewModel = hiltViewModel()
) {
    val state by eqVm.uiState.collectAsState()
    val presets = state.presets
    val selectedId by eqVm.headphoneEqPresetId.collectAsState(initial = -1L)
    val selected = presets.firstOrNull { it.id == selectedId }
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Headset,
            contentDescription = null,
            tint = TealAccent,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Headphone EQ preset",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                if (selected != null)
                    "Auto-applies '${selected.name}' on headphone connect"
                else
                    "Off — global EQ stays active even with headphones",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Headphone EQ preset", color = TealAccent) },
            text = {
                Column {
                    Text(
                        "Pick a preset that auto-applies the moment wired " +
                            "or Bluetooth headphones connect. Disconnects " +
                            "restore your manually-selected preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    TextButton(
                        onClick = {
                            eqVm.setHeadphoneEqPreset(-1L)
                            showDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Off — never auto-swap",
                            color = if (selectedId == -1L) TealAccent else TextPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    presets.forEach { preset ->
                        TextButton(
                            onClick = {
                                eqVm.setHeadphoneEqPreset(preset.id)
                                showDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                preset.name,
                                color = if (selectedId == preset.id) TealAccent else TextPrimary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Done", color = TealAccent)
                }
            },
            containerColor = OledBlack
        )
    }
}
