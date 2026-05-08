package com.powermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.powermediaplayer.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * §C18 — Track-gain vs Album-gain mode picker. Locked spec: two modes,
 * each targeting -14 LUFS. Track-gain (default) normalises every file
 * independently; Album-gain preserves the artist's intended dynamic
 * range across the album by using a shared per-album gain.
 */
@Composable
fun ReplayGainModeRow(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ds = viewModel.settingsDataStore
    val mode by ds.replayGainMode.collectAsState(initial = "track")
    val scope = rememberCoroutineScope()
    Text(
        "ReplayGain mode (locked target -14 LUFS):",
        color = TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = mode == "track",
            onClick = { scope.launch { ds.setReplayGainMode("track") } },
            label = { Text("Track gain") }
        )
        FilterChip(
            selected = mode == "album",
            onClick = { scope.launch { ds.setReplayGainMode("album") } },
            label = { Text("Album gain") }
        )
    }
}
