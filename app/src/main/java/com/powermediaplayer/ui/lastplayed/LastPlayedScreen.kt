package com.powermediaplayer.ui.lastplayed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.powermediaplayer.util.TimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val DriveBlue = Color(0xFF4FC3F7)
private val SpotifyGreen = Color(0xFF1DB954)
private val LocalTeal = TealAccent

private const val RECENT_BOOKMARK_CAP = 5

@Composable
fun LastPlayedScreen(
    onNavigateToPlayer: () -> Unit,
    viewModel: LastPlayedViewModel = hiltViewModel()
) {
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val dynamic by viewModel.dynamic.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showInfoSheet by remember { mutableStateOf(false) }
    var contextItem by remember { mutableStateOf<HistoryItem?>(null) }
    var contextFromRecents by remember { mutableStateOf(true) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (showInfoSheet) {
        com.powermediaplayer.ui.info.InfoSheet(
            data = com.powermediaplayer.ui.info.lastPlayedInfo,
            onDismiss = { showInfoSheet = false }
        )
    }
    contextItem?.let { item ->
        com.powermediaplayer.ui.player.components.TrackContextSheet(
            title = item.title,
            subtitle = item.subtitle,
            actions = com.powermediaplayer.ui.player.components.TrackContextActions(
                onFavourite = if (contextFromRecents && !item.isPinned) {
                    {
                        scope.launch {
                            val ok = viewModel.pinSession(item.id)
                            if (!ok) snackbar.showSnackbar("Favourites full (10/10) — unpin one first")
                        }
                        contextItem = null
                    }
                } else null,
                onUnfavourite = if (!contextFromRecents) {
                    { viewModel.unpin(item.id); contextItem = null }
                } else null,
                onShare = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(android.content.Intent.EXTRA_TEXT, item.mediaUri)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, "Share"))
                    contextItem = null
                },
                onDelete = if (contextFromRecents) {
                    { viewModel.deleteRecentsRow(item.id); contextItem = null }
                } else null
            ),
            onDismiss = { contextItem = null }
        )
    }

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
                Spacer(Modifier.weight(1f))
                com.powermediaplayer.ui.info.InfoIcon(
                    onClick = { showInfoSheet = true }
                )
            }

            // Pinned section — drag-to-reorder.
            if (pinned.isNotEmpty()) {
                SectionHeader("Pinned (${pinned.size}/10)")
                ReorderablePinnedList(
                    items = pinned,
                    onMove = { from, to ->
                        val movedFavId = pinned[from].id
                        viewModel.reorderPinned(movedFavId, to)
                    },
                    onLongClick = { item ->
                        contextItem = item
                        contextFromRecents = false
                    },
                    onTap = { item ->
                        viewModel.playLocalAt(item)
                        onNavigateToPlayer()
                    },
                    onTapBookmark = { item, bookmark ->
                        viewModel.playLocalAt(item, atPositionMs = bookmark.positionMs)
                        onNavigateToPlayer()
                    },
                    onDeleteBookmark = { id -> viewModel.deletePinnedBookmark(id) },
                    onUnpin = { favId -> viewModel.unpin(favId) },
                    bookmarkProvider = { favId -> viewModel.pinnedBookmarksFor(favId) }
                )
            }

            // Recent (dynamic) section header. Clear-all button on the
            // trailing edge wipes every Recents row + their session
            // bookmarks. Pinned snapshots are independent and survive.
            RecentsSectionHeader(
                visible = dynamic.isNotEmpty(),
                emptyLabel = "No recent items",
                onClearAll = { viewModel.clearAllRecents() }
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(dynamic, key = { _, it -> "dyn_${it.id}" }) { _, item ->
                    SwipeToDismissRow(
                        onDismissed = { viewModel.deleteRecentsRow(item.id) }
                    ) {
                        HistoryRowWithBookmarks(
                            item = item,
                            bookmarkCap = RECENT_BOOKMARK_CAP,
                            bookmarkProvider = { viewModel.recentsBookmarksFor(item.id) },
                            onTap = {
                                viewModel.playLocalAt(item)
                                onNavigateToPlayer()
                            },
                            onTapBookmark = { bookmark ->
                                viewModel.playLocalAt(item, atPositionMs = bookmark.positionMs)
                                onNavigateToPlayer()
                            },
                            onDeleteBookmark = { id -> viewModel.deleteRecentsBookmark(id) },
                            onLongClick = {
                                contextItem = item
                                contextFromRecents = true
                            },
                            swipeBookmark = true,
                            trailing = {
                                IconButton(onClick = {
                                    scope.launch {
                                        val ok = viewModel.pinSession(item.id)
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
                            }
                        )
                    }
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

/**
 * Recents header with an inline "Clear all" button. Hidden when the
 * list is empty (replaced by the empty-state label so the user
 * doesn't accidentally tap it on an already-empty list).
 */
@Composable
private fun RecentsSectionHeader(
    visible: Boolean,
    emptyLabel: String,
    onClearAll: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (visible) "Recent" else emptyLabel,
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (visible) {
            TextButton(onClick = onClearAll) {
                Icon(
                    Icons.Filled.ClearAll,
                    contentDescription = null,
                    tint = TealAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Clear all", color = TealAccent, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Swipe-left-to-dismiss wrapper. Threshold is 40 % of width so a
 * casual brush doesn't accidentally remove a row. Right-to-left swipes
 * only — left-to-right is disabled to leave that gesture available
 * for system back / drawers without conflict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissRow(
    onDismissed: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDismissed()
                true
            } else false
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.4f }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFFFF5252)
                )
            }
        }
    ) {
        content()
    }
}

@Composable
private fun ReorderablePinnedList(
    items: List<HistoryItem>,
    onMove: (Int, Int) -> Unit,
    onTap: (HistoryItem) -> Unit,
    onTapBookmark: (HistoryItem, LastPlayedViewModel.BookmarkRow) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onUnpin: (Long) -> Unit,
    bookmarkProvider: (Long) -> Flow<List<LastPlayedViewModel.BookmarkRow>>,
    onLongClick: (HistoryItem) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
    ) {
        itemsIndexed(items, key = { _, it -> "pin_${it.id}" }) { _, item ->
            ReorderableItem(reorderState, key = "pin_${item.id}") { dragging ->
                HistoryRowWithBookmarks(
                    item = item,
                    bookmarkCap = Int.MAX_VALUE, // pinned: unlimited
                    bookmarkProvider = { bookmarkProvider(item.id) },
                    onTap = { onTap(item) },
                    onTapBookmark = { bookmark -> onTapBookmark(item, bookmark) },
                    onDeleteBookmark = onDeleteBookmark,
                    onLongClick = { onLongClick(item) },
                    elevated = dragging,
                    trailing = {
                        IconButton(onClick = { onUnpin(item.id) }) {
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
                    }
                )
            }
        }
    }
}

/**
 * History row with an expand-to-reveal bookmark dropdown. The
 * top portion is the play-this-track-from-its-saved-position row;
 * tapping the chevron expands to show up to [bookmarkCap] bookmarks
 * (most-recent first), each tappable to seek the file to that
 * timestamp. Local + Drive (SAF) sources play through the local
 * ExoPlayer; Spotify rows route via Spotify Connect (in VM).
 */
@Composable
private fun HistoryRowWithBookmarks(
    item: HistoryItem,
    bookmarkCap: Int,
    bookmarkProvider: () -> Flow<List<LastPlayedViewModel.BookmarkRow>>,
    onTap: () -> Unit,
    onTapBookmark: (LastPlayedViewModel.BookmarkRow) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    trailing: @Composable RowScope.() -> Unit,
    onLongClick: () -> Unit = {},
    elevated: Boolean = false,
    /**
     * When true, each bookmark in the dropdown is swipe-to-dismiss
     * (in addition to the inline delete icon). Recents rows opt in;
     * Pinned rows leave it false because the swipe gesture conflicts
     * with the drag-to-reorder handle.
     */
    swipeBookmark: Boolean = false
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val bookmarksFlow = remember(item.id) { bookmarkProvider() }
    val bookmarks by bookmarksFlow.collectAsState(initial = emptyList())

    Surface(
        color = if (elevated) TealAccent.copy(alpha = 0.10f) else SurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HistoryHeaderRow(
                item = item,
                bookmarkCount = bookmarks.size,
                expandable = bookmarks.isNotEmpty(),
                expanded = expanded,
                onToggleExpanded = { expanded = !expanded },
                onClick = onTap,
                onLongClick = onLongClick,
                trailing = trailing
            )
            AnimatedVisibility(visible = expanded && bookmarks.isNotEmpty()) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 12.dp, bottom = 8.dp)) {
                    bookmarks.take(bookmarkCap).forEach { bookmark ->
                        if (swipeBookmark) {
                            key(bookmark.id) {
                                SwipeToDismissRow(
                                    onDismissed = { onDeleteBookmark(bookmark.id) }
                                ) {
                                    BookmarkRowUi(
                                        bookmark = bookmark,
                                        onClick = { onTapBookmark(bookmark) },
                                        onDelete = { onDeleteBookmark(bookmark.id) }
                                    )
                                }
                            }
                        } else {
                            BookmarkRowUi(
                                bookmark = bookmark,
                                onClick = { onTapBookmark(bookmark) },
                                onDelete = { onDeleteBookmark(bookmark.id) }
                            )
                        }
                    }
                    if (bookmarks.size > bookmarkCap) {
                        Text(
                            "+${bookmarks.size - bookmarkCap} more — pin to see all",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryHeaderRow(
    item: HistoryItem,
    bookmarkCount: Int,
    expandable: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit
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
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                    Text("@${TimeFormatter.formatDuration(item.lastPositionMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary)
                }
                if (bookmarkCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = TealAccent,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(" $bookmarkCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = TealAccent)
                }
            }
        }
        if (expandable) {
            IconButton(onClick = onToggleExpanded, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide bookmarks" else "Show bookmarks",
                    tint = TextSecondary
                )
            }
        }
        trailing()
    }
}

@Composable
private fun BookmarkRowUi(
    bookmark: LastPlayedViewModel.BookmarkRow,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Play from this bookmark",
            tint = TealAccent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            TimeFormatter.formatDuration(bookmark.positionMs),
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        if (bookmark.label.isNotBlank() && bookmark.label != TimeFormatter.formatDuration(bookmark.positionMs)) {
            Spacer(Modifier.width(8.dp))
            Text(
                bookmark.label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete bookmark",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
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
