package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.settings.SettingsViewModel
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary

/**
 * Player overlay button for video effects (mirror H/V, B&W, rotation).
 * Visible only on the video Player layout (caller decides). Tapping
 * opens a bottom sheet of toggles backed by SettingsDataStore via
 * SettingsViewModel — same backing store as before; only the entry
 * point moved off the Settings tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEffectsButton(
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel = hiltViewModel()
) {
    val s by settingsVm.uiState.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    var lastInteract by remember { mutableStateOf(0L) }
    val anyOn = s.videoFlipH || s.videoFlipV || s.videoBw ||
        s.videoSepia || s.videoInvert || s.videoRotation != 0
    fun touch() { lastInteract = System.currentTimeMillis() }
    // 3-second auto-dismiss; the timer restarts whenever the user
    // interacts with any control inside the sheet (lastInteract bump).
    LaunchedEffect(showSheet, lastInteract) {
        if (showSheet) {
            kotlinx.coroutines.delay(3000)
            showSheet = false
        }
    }

    IconButton(
        onClick = { showSheet = true; touch() },
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Tune,
            contentDescription = "Video effects",
            // Always teal so the button matches the surrounding control
            // strip; "on" state stays at full opacity, "off" dims to
            // 60 % so the user still sees a visual difference.
            tint = if (anyOn) TealAccent else TealAccent.copy(alpha = 0.6f)
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
                    "Video effects",
                    style = MaterialTheme.typography.titleMedium,
                    color = TealAccent
                )
                Spacer(Modifier.height(8.dp))
                EffectToggleRow(
                    icon = Icons.Filled.Flip,
                    label = "Mirror horizontally",
                    on = s.videoFlipH,
                    onChange = { settingsVm.setVideoFlipH(it); touch() }
                )
                EffectToggleRow(
                    icon = Icons.Filled.Flip,
                    label = "Flip vertically (upside-down)",
                    on = s.videoFlipV,
                    onChange = { settingsVm.setVideoFlipV(it); touch() }
                )
                EffectToggleRow(
                    icon = Icons.Filled.Tonality,
                    label = "Black & white",
                    on = s.videoBw,
                    onChange = { settingsVm.setVideoBw(it); touch() }
                )
                EffectToggleRow(
                    icon = Icons.Filled.FilterDrama,
                    label = "Sepia",
                    on = s.videoSepia,
                    onChange = { settingsVm.setVideoSepia(it); touch() }
                )
                EffectToggleRow(
                    icon = Icons.Filled.InvertColors,
                    label = "Invert colours",
                    on = s.videoInvert,
                    onChange = { settingsVm.setVideoInvert(it); touch() }
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.RotateRight, contentDescription = null,
                        tint = TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rotation", style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary, modifier = Modifier.weight(1f))
                    listOf(0, 90, 180, 270).forEach { deg ->
                        FilterChip(
                            selected = s.videoRotation == deg,
                            onClick = { settingsVm.setVideoRotation(deg); touch() },
                            label = { Text("${deg}°") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun EffectToggleRow(
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
