package com.powermediaplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import com.powermediaplayer.ui.theme.parseHexColor

/**
 * §11.5 — Theme settings: user-selectable accent colour. Background
 * stays black and text stays greyscale (locked in spec for OLED-power
 * + contrast guarantees). Two entry modes: hex field + 8-preset
 * row of swatches. Reset returns to the original teal (`""` hex).
 *
 * Live preview: every other UI surface that consumes `TealAccent`
 * recomposes the moment the user commits a new value, because
 * `TealAccent` is now backed by a Compose MutableState (see
 * `ui/theme/Color.kt`).
 */
@Composable
fun ThemeSection(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var hexInput by remember(state.themeAccentHex) {
        mutableStateOf(state.themeAccentHex)
    }
    val parsed = parseHexColor(hexInput) ?: TealAccent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Palette,
                contentDescription = null,
                tint = TealAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Accent colour",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    "Tints icons, sliders, and highlights. Background " +
                        "stays black and text stays greyscale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            // Live swatch.
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(parsed)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hexInput,
                onValueChange = { hexInput = it },
                label = { Text("#RRGGBB") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                if (parseHexColor(hexInput) != null) {
                    viewModel.setThemeAccentHex(hexInput)
                }
            }) { Text("Apply", color = TealAccent) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Presets:",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            PRESETS.forEach { (label, hex) ->
                val color = parseHexColor(hex) ?: Color.Gray
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable {
                            hexInput = hex
                            viewModel.setThemeAccentHex(hex)
                        }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            hexInput = ""
            viewModel.setThemeAccentHex("")
        }) {
            Text("Reset to default teal", color = TextSecondary)
        }
    }
}

/**
 * Eight preset accents (spec §11.5.2). Hex strings stay raw so the
 * Apply path runs the same parse + persist logic as a manual entry.
 */
private val PRESETS = listOf(
    "Teal" to "#00BFA5",
    "Amber" to "#FFB300",
    "Indigo" to "#5C6BC0",
    "Magenta" to "#E91E63",
    "Cyan" to "#00ACC1",
    "Lime" to "#C0CA33",
    "Rose" to "#F06292",
    "Slate" to "#78909C"
)
