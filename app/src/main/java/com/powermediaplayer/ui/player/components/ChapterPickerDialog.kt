package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.powermediaplayer.service.ChapterInfo
import com.powermediaplayer.service.QueueItemInfo
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.TimeFormatter

/**
 * Dialog for selecting a chapter within the current track,
 * or a specific track within the current playlist.
 *
 * Shows chapter list when chapters are available (e.g. audiobooks, podcast episodes,
 * long video files). Falls back to playlist track list when no chapters.
 */
@Composable
fun ChapterPickerDialog(
    // Chapter mode
    chapters: List<ChapterInfo>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit,
    // Track mode (playlist)
    playlist: List<QueueItemInfo>,
    currentTrackIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hasChapters = chapters.isNotEmpty()
    val hasPlaylist = playlist.size > 1

    // Auto-scroll to current item
    val listState = rememberLazyListState()
    val currentIdx = if (hasChapters) currentChapterIndex else currentTrackIndex
    LaunchedEffect(currentIdx) {
        if (currentIdx >= 0) listState.animateScrollToItem(currentIdx)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        titleContentColor = TealAccent,
        textContentColor = TextPrimary,
        title = {
            Text(
                text = if (hasChapters) "Chapters" else "Tracks",
                style = MaterialTheme.typography.titleLarge,
                color = TealAccent
            )
        },
        text = {
            if (!hasChapters && !hasPlaylist) {
                Text(
                    text = "No chapters or tracks available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    if (hasChapters) {
                        itemsIndexed(
                            chapters,
                            key = { i, c -> "${i}-${c.startTimeMs}" }
                        ) { index, chapter ->
                            val isCurrent = index == currentChapterIndex
                            ChapterRow(
                                index = index + 1,
                                title = chapter.title,
                                startTime = TimeFormatter.formatDuration(chapter.startTimeMs),
                                isCurrent = isCurrent,
                                onClick = {
                                    onChapterSelected(index)
                                    onDismiss()
                                }
                            )
                        }
                    } else {
                        itemsIndexed(
                            playlist,
                            key = { i, t -> "${i}-${t.title}" }
                        ) { index, track ->
                            val isCurrent = index == currentTrackIndex
                            TrackRow(
                                index = index + 1,
                                title = track.title,
                                artist = track.artist,
                                duration = if (track.durationMs > 0) TimeFormatter.formatDuration(track.durationMs) else "",
                                isCurrent = isCurrent,
                                onClick = {
                                    onTrackSelected(index)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    startTime: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isCurrent) TealAccent.copy(alpha = 0.12f) else SurfaceElevated
    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCurrent) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = null,
                tint = if (isCurrent) TealAccent else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) TealAccent else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = startTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    title: String,
    artist: String,
    duration: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isCurrent) TealAccent.copy(alpha = 0.12f) else SurfaceElevated
    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrent) TealAccent else TextSecondary,
                modifier = Modifier.width(28.dp)
            )
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (isCurrent) TealAccent else DisabledGrey,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) TealAccent else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotEmpty()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (duration.isNotEmpty()) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        }
    }
}
