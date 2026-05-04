package com.powermediaplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextSecondary

/**
 * A generalized, reusable, legibly labeled icon action for use in menus, sheets, or toolbars.
 * The coordinator agent can place this in a Row, Grid, or BottomSheet.
 */
@Composable
fun LabeledActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TealAccent,
    textColor: Color = TextSecondary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * A specialized labeled action to toggle favorites in the media selection section.
 */
@Composable
fun FavoriteLabeledAction(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder
    val label = if (isFavorite) "Favourited" else "Favourite"
    val tint = if (isFavorite) TealAccent else TealAccent.copy(alpha = 0.6f)

    LabeledActionIcon(
        icon = icon,
        label = label,
        onClick = onToggle,
        modifier = modifier,
        tint = tint
    )
}

/**
 * Sorting labeled action, ready for the coordinator agent to place in a sorting UI.
 */
@Composable
fun SortLabeledAction(
    sortCriterionName: String,
    isActive: Boolean,
    isAscending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = if (isActive) {
        if (isAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward
    } else {
        Icons.Filled.Sort
    }
    
    val tint = if (isActive) TealAccent else TextSecondary

    LabeledActionIcon(
        icon = icon,
        label = sortCriterionName,
        onClick = onClick,
        modifier = modifier,
        tint = tint,
        textColor = if (isActive) TealAccent else TextSecondary
    )
}

/**
 * Highly legible labeled icon designed to represent the type of media (Audio/Video).
 * This can replace the unlabeled generic icons in the media selection section rows.
 */
@Composable
fun MediaFormatBadge(
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isVideo) Icons.Filled.VideoFile else Icons.Filled.AudioFile,
            contentDescription = if (isVideo) "Video File" else "Audio File",
            tint = TealAccent,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isVideo) "VIDEO" else "AUDIO",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = TealAccent,
            maxLines = 1
        )
    }
}

/**
 * Additional standard media actions prepared for the coordinator agent.
 */
@Composable
fun MediaActionRowPrepared(
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        LabeledActionIcon(
            icon = Icons.Filled.SkipNext,
            label = "Play Next",
            onClick = onPlayNext
        )
        LabeledActionIcon(
            icon = Icons.Filled.PlaylistAdd,
            label = "Playlist",
            onClick = onAddToPlaylist
        )
        LabeledActionIcon(
            icon = Icons.Filled.Share,
            label = "Share",
            onClick = onShare
        )
        LabeledActionIcon(
            icon = Icons.Filled.Delete,
            label = "Delete",
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error
        )
    }
}
