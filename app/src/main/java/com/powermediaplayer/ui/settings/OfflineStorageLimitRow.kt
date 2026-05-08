package com.powermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextTertiary
import kotlinx.coroutines.launch

private const val GB = 1024L * 1024 * 1024

@Composable
fun OfflineStorageLimitRow(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ds = viewModel.settingsDataStore
    val limit by ds.offlineStorageLimitBytes.collectAsState(initial = 5L * GB)
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CloudDone, contentDescription = null,
            tint = TealAccent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Offline storage limit",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                "When a Save Offline takes you over the limit, oldest " +
                    "unstarred copies are evicted first.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf<Pair<String, Long>>(
            "1 GB" to GB, "5 GB" to 5L * GB,
            "10 GB" to 10L * GB, "Unlimited" to 0L
        ).forEach { (label, bytes) ->
            FilterChip(
                selected = limit == bytes,
                onClick = {
                    scope.launch { ds.setOfflineStorageLimitBytes(bytes) }
                },
                label = { Text(label) }
            )
        }
    }
}
