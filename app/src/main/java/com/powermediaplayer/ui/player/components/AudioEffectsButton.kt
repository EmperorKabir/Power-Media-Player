package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.settings.SettingsViewModel
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary

/**
 * Player-overlay button for audio effects — counterpart to
 * [VideoEffectsButton] but always visible (works for both audio and
 * video tracks since reverb / channel effects apply to any audio
 * stream). Tapping opens a bottom sheet with reverb preset chips,
 * stereo flip + mono mix toggles, and a multi-channel passthrough
 * toggle. All controls write through SettingsViewModel so the same
 * state survives in Settings → Audio effects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEffectsButton(
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel = hiltViewModel()
) {
    val s by settingsVm.uiState.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val anyOn = s.reverbPreset != 0 || s.stereoFlip || s.monoMix

    IconButton(
        onClick = { showSheet = true },
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = "Audio effects",
            tint = if (anyOn) TealAccent else TextSecondary
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = androidx.compose.ui.graphics.Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Audio effects",
                    style = MaterialTheme.typography.titleMedium,
                    color = TealAccent
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Reverb",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                val reverbOptions = listOf(
                    0 to "Off",
                    1 to "Room",
                    2 to "Med Hall",
                    3 to "Lrg Hall",
                    4 to "Plate",
                    5 to "Cave"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    reverbOptions.forEach { (id, label) ->
                        FilterChip(
                            selected = s.reverbPreset == id,
                            onClick = { settingsVm.setReverbPreset(id) },
                            label = {
                                Text(
                                    label,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                AudioEffectToggleRow(
                    icon = Icons.Filled.SwapHoriz,
                    label = "Stereo flip (L↔R)",
                    on = s.stereoFlip,
                    onChange = { settingsVm.setStereoFlip(it) }
                )
                AudioEffectToggleRow(
                    icon = Icons.Filled.Adjust,
                    label = "Mono mix",
                    on = s.monoMix,
                    onChange = { settingsVm.setMonoMix(it) }
                )
                AudioEffectToggleRow(
                    icon = Icons.Filled.Speaker,
                    label = "Multi-channel passthrough",
                    on = s.passthroughAudio,
                    onChange = { settingsVm.setPassthroughAudio(it) }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AudioEffectToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall,
            color = TextPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TealAccent,
                checkedTrackColor = TealAccent.copy(alpha = 0.4f)
            )
        )
    }
}
