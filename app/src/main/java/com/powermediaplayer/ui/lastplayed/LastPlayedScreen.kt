package com.powermediaplayer.ui.lastplayed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.data.repository.LastPlayedRepository.HistoryItem
import com.powermediaplayer.data.repository.LastPlayedRepository.Source
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val DriveBlue = Color(0xFF4FC3F7)
private val SpotifyGreen = Color(0xFF1DB954)
private val LocalTeal = TealAccent

@Composable
fun LastPlayedScreen(
    onNavigateToPlayer: () -> Unit,
    viewModel: LastPlayedViewModel = hiltViewModel()
) {
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val dynamic by viewModel.dynamic.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = OledBlack,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(OledBlack)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.History, contentDescription = null,
                    tint = TealAccent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("Last Played",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TealAccent, fontWeight = FontWeight.SemiBold)
            }

            // Pinned section — drag-to-reorder.
            if (pinned.isNotEmpty()) {
                SectionHeader("Pinned (${pinned.size}/10)")
                ReorderablePinnedList(
                    items = pinned,
                    onMove = { from, to ->
                        val movedUri = pinned[from].mediaUri
                        viewModel.reorderPinned(movedUri, to)
                    },
                    onTap = { item ->
                        viewModel.playLocalAt(item)
                        onNavigateToPlayer()
                    },
                    onUnpin = { uri -> viewModel.unpin(uri) }
                )
            }

            // Recent (dynamic) section.
            SectionHeader(if (dynamic.isEmpty()) "No recent items" else "Recent")
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(dynamic, key = { _, it -> "dyn_${it.mediaUri}" }) { _, item ->
                    HistoryRow(
                        item = item,
                        trailing = {
                            IconButton(onClick = {
                                scope.launch {
                                    val ok = viewModel.pin(item.mediaUri)
                                    if (!ok) {
                                        snackbar.showSnackbar(
                                            "Favourites full (10/10) — unpin one first"
                                        )
                                    }
                                }
                            }) {
                                Icon(
                                    if (item.isPinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = "Pin",
                                    tint = if (item.isPinned) TealAccent else TextSecondary
                                )
                            }
                        },
                        onClick = {
                            viewModel.playLocalAt(item)
                            onNavigateToPlayer()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun ReorderablePinnedList(
    items: List<HistoryItem>,
    onMove: (Int, Int) -> Unit,
    onTap: (HistoryItem) -> Unit,
    onUnpin: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = (items.size * 72).dp.coerceAtLeast(72.dp))
    ) {
        itemsIndexed(items, key = { _, it -> "pin_${it.mediaUri}" }) { _, item ->
            ReorderableItem(reorderState, key = "pin_${item.mediaUri}") { dragging ->
                HistoryRow(
                    item = item,
                    trailing = {
                        IconButton(onClick = { onUnpin(item.mediaUri) }) {
                            Icon(Icons.Filled.Star, contentDescription = "Unpin",
                                tint = TealAccent)
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.draggableHandle()
                        ) {
                            Icon(Icons.Filled.DragHandle, contentDescription = "Reorder",
                                tint = TextSecondary)
                        }
                    },
                    onClick = { onTap(item) },
                    elevated = dragging
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    trailing: @Composable RowScope.() -> Unit,
    onClick: () -> Unit,
    elevated: Boolean = false
) {
    Surface(
        color = if (elevated) TealAccent.copy(alpha = 0.10f) else SurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OledBlack),
                contentAlignment = Alignment.Center
            ) {
                if (item.artworkUri != null) {
                    coil3.compose.AsyncImage(
                        model = item.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null,
                        tint = TealAccent, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onClick() }
            ) {
                Text(item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourcePill(item.source)
                    if (item.subtitle.isNotBlank() && item.subtitle != item.source.name) {
                        Spacer(Modifier.width(6.dp))
                        Text(item.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.lastPositionMs > 0L) {
                        Spacer(Modifier.width(6.dp))
                        Text("@${formatMs(item.lastPositionMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary)
                    }
                }
            }
            trailing()
        }
    }
}

@Composable
private fun SourcePill(source: Source) {
    val (label, color) = when (source) {
        Source.LOCAL -> "Local" to LocalTeal
        Source.DRIVE -> "Drive" to DriveBlue
        Source.SPOTIFY -> "Spotify" to SpotifyGreen
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val rem = s % 60
    return "%d:%02d".format(m, rem)
}

