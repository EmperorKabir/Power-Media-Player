package com.powermediaplayer.ui.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * #5 — bounded, drag-reorderable list of subscribed shows. Self-contained (not
 * a lift of LastPlayedScreen.ReorderablePinnedList, which is coupled to
 * HistoryItem/bookmarks): reuses the same sh.calvin.reorderable 2.5.0
 * primitives. Height-capped so the nested scroll never traps inside an unbounded
 * parent (T313/A3). Tapping a row toggles its expanded body; the body is
 * rendered by the caller BELOW this list so it is not clipped by the cap.
 */
@Composable
fun ReorderableShowList(
    shows: List<PodcastShowEntity>,
    counts: Map<String, PodcastDao.FeedCounts>,
    expandedFeed: String?,
    onToggleExpand: (String) -> Unit,
    onUnsubscribe: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(shows, key = { _, s -> "show_${s.feedUrl}" }) { _, show ->
            ReorderableItem(reorderState, key = "show_${show.feedUrl}") { _ ->
                val c = counts[show.feedUrl]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(show.feedUrl) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PodcastArtwork(show.artworkUrl, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            show.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 2
                        )
                        val total = c?.total ?: 0
                        val newCount = c?.unopened ?: 0
                        Text(
                            "$total episode${if (total == 1) "" else "s"}" +
                                if (newCount > 0) " · $newCount new" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { onUnsubscribe(show.feedUrl) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Unsubscribe", tint = ErrorRed)
                    }
                    IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Reorder ${show.title}",
                            tint = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
