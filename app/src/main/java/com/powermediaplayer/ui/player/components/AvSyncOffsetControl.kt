package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextTertiary

/**
 * Shared A/V sync-offset slider used in BOTH the Cast and Bluetooth player
 * popups. The same stored value is also editable in Settings, so the two
 * stay in sync (both write the one DataStore key via SettingsViewModel).
 *
 * The plain Material3 [Slider] + [TealAccent] labels recolour automatically
 * when the user changes the theme accent (colorScheme.primary IS the accent,
 * rebuilt on accent change), so this control is accent-robust for free.
 *
 * No outer horizontal padding — the hosting sheet supplies it, so the title /
 * description / track all align to the sheet's edge.
 *
 * @param offsetMs current value, ±1000 ms.
 */
@Composable
fun AvSyncOffsetControl(
    title: String,
    description: String,
    offsetMs: Int,
    onOffsetChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Offset",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${offsetMs} ms",
                style = MaterialTheme.typography.labelMedium,
                color = TealAccent
            )
            if (offsetMs != 0) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { onOffsetChange(0) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset offset to 0",
                        tint = TealAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Slider(
            value = offsetMs.toFloat().coerceIn(-1000f, 1000f),
            onValueChange = { onOffsetChange(it.toInt()) },
            valueRange = -1000f..1000f,
            steps = 199, // 10 ms increments
            modifier = Modifier.fillMaxWidth()
        )
    }
}
