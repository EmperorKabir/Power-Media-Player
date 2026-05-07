package com.powermediaplayer.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.powermediaplayer.data.db.dao.PlaybackHistoryDao
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Snapshot bundle aggregated from PlaybackHistoryDao. */
data class StatsSnapshot(
    val totalPlays: Int = 0,
    val totalListenedMs: Long = 0L,
    val longestTrackMs: Long = 0L,
    val topTitles: List<PlaybackHistoryDao.CountRow> = emptyList(),
    val topSubtitles: List<PlaybackHistoryDao.CountRow> = emptyList()
)

private suspend fun loadStats(dao: PlaybackHistoryDao): StatsSnapshot = withContext(Dispatchers.IO) {
    StatsSnapshot(
        totalPlays = dao.totalPlays(),
        totalListenedMs = dao.totalListenedMs(),
        longestTrackMs = dao.longestTrackDurationMs() ?: 0L,
        topTitles = dao.topTitles(),
        topSubtitles = dao.topSubtitles()
    )
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 min"
    val totalMin = ms / 60_000L
    val hours = totalMin / 60
    val mins = totalMin % 60
    return when {
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins} min"
    }
}

/**
 * §C2 — listening-stats dashboard. ModalBottomSheet aggregating
 * playback_history into a snapshot of total plays, total listened
 * time (sum of lastPositionMs as a rough proxy), top-5 titles, top-5
 * subtitles. Loaded lazily in a LaunchedEffect so the user pays the
 * query cost only when they open the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsSheet(
    dao: PlaybackHistoryDao,
    onDismiss: () -> Unit
) {
    var snapshot by remember { mutableStateOf<StatsSnapshot?>(null) }
    LaunchedEffect(Unit) {
        snapshot = loadStats(dao)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Listening stats",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent
            )
            Text(
                text = "Aggregated from your playback history. Times shown are " +
                    "based on each track's last saved position.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(Modifier.height(12.dp))

            val s = snapshot
            if (s == null) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                StatRow("Total plays", "${s.totalPlays}")
                StatRow("Time listened (rough)", formatDuration(s.totalListenedMs))
                StatRow("Longest track", formatDuration(s.longestTrackMs))
                Spacer(Modifier.height(12.dp))

                if (s.topTitles.isNotEmpty()) {
                    Text(
                        text = "Top titles",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                    s.topTitles.forEach { row ->
                        StatRow(row.label, "${row.plays} ×")
                    }
                }

                if (s.topSubtitles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Top artists / sources",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                    s.topSubtitles.forEach { row ->
                        StatRow(row.label, "${row.plays} ×")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TealAccent
        )
    }
}
