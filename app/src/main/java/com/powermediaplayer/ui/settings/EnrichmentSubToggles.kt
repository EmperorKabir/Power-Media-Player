package com.powermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * §C17 — sub-toggles + apply scope. Visibility gated on the master
 * "Online metadata enrichment" toggle in SettingsScreen.
 */
@Composable
fun EnrichmentSubToggles(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ds = viewModel.settingsDataStore
    val provider by ds.metadataEnrichmentProvider.collectAsState(initial = "musicbrainz")
    val artwork by ds.enrichFetchArtwork.collectAsState(initial = true)
    val year by ds.enrichFetchYear.collectAsState(initial = true)
    val genre by ds.enrichFetchGenre.collectAsState(initial = true)
    val scope by ds.enrichApplyScope.collectAsState(initial = "missing_only")
    val cs = rememberCoroutineScope()
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            "Provider:", color = TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = provider == "musicbrainz",
                onClick = { cs.launch { ds.setMetadataEnrichmentProvider("musicbrainz") } },
                label = { Text("MusicBrainz") }
            )
            FilterChip(
                selected = provider == "discogs",
                onClick = { cs.launch { ds.setMetadataEnrichmentProvider("discogs") } },
                label = { Text("Discogs") }
            )
            FilterChip(
                selected = provider == "both",
                onClick = { cs.launch { ds.setMetadataEnrichmentProvider("both") } },
                label = { Text("Both") }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = artwork, onCheckedChange = { cs.launch { ds.setEnrichFetchArtwork(it) } })
            Text("  Fetch missing artwork", color = TextPrimary,
                modifier = Modifier.padding(top = 12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = year, onCheckedChange = { cs.launch { ds.setEnrichFetchYear(it) } })
            Text("  Fetch missing year", color = TextPrimary,
                modifier = Modifier.padding(top = 12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = genre, onCheckedChange = { cs.launch { ds.setEnrichFetchGenre(it) } })
            Text("  Fetch missing genre", color = TextPrimary,
                modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            "Apply to:", color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = scope == "all",
                onClick = { cs.launch { ds.setEnrichApplyScope("all") } },
                label = { Text("All files") }
            )
            FilterChip(
                selected = scope == "missing_only",
                onClick = { cs.launch { ds.setEnrichApplyScope("missing_only") } },
                label = { Text("Files with no embedded tags") }
            )
        }
    }
}
