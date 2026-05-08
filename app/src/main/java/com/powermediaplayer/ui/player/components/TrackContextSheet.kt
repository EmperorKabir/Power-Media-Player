package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary

/**
 * Per-row action callback bag for [TrackContextSheet]. Each callback is
 * nullable; null callbacks hide the corresponding menu item entirely.
 *
 * Caller decides item visibility — typically:
 *  - Favourite/Unfavourite: pass exactly ONE based on current state.
 *  - Override*: pass non-null only when track is starred or pinned
 *    (per Q10 LOCKED — manually starred/pinned only). Wired in Library
 *    + LastPlayed; partially wired in Cloud (SAF-content:// only).
 *  - Edit tags: not wired anywhere yet — tag-editor screen is on the
 *    deferred list. Leave null at every call site for now.
 */
data class TrackContextActions(
    val onFavourite: (() -> Unit)? = null,
    val onUnfavourite: (() -> Unit)? = null,
    val onHide: (() -> Unit)? = null,
    val onAddToQueueNext: (() -> Unit)? = null,
    val onEditTags: (() -> Unit)? = null,
    val onOverrideSpeed: (() -> Unit)? = null,
    val onOverrideAudio: (() -> Unit)? = null,
    val onOverrideVideo: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onOpenInOtherApp: (() -> Unit)? = null,
    val onSaveOffline: (() -> Unit)? = null,
    val onRemoveOffline: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/**
 * Long-press track-context menu. Opens as a `ModalBottomSheet` (Q4
 * LOCKED Option B — bottom sheet, not dropdown). Menu items render
 * top-to-bottom in a fixed order:
 *   Favourite/Unfavourite, Hide, Add to queue next, Edit tags,
 *   Override speed, Override audio effects, Override video effects,
 *   Share, Delete.
 *
 * Items whose callback is null in [actions] are not rendered. Delete
 * stays visually distinguished (ErrorRed tint) so an accidental tap is
 * less likely.
 *
 * Bottom-sheet auto-dismiss: caller-driven via [onDismiss]. The sheet
 * does NOT auto-close on action taps — the caller decides whether to
 * dismiss after invoking the action callback. Recommended pattern:
 *   onFavourite = { vm.toggleFav(uri); showContextSheet = false }.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextSheet(
    title: String,
    subtitle: String,
    actions: TrackContextActions,
    onDismiss: () -> Unit,
    settingsVm: com.powermediaplayer.ui.settings.SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val sState by settingsVm.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(sState.trackContextSheetHideSec) {
        // 0 = Never. Positive value: dismiss after that many seconds
        // of sheet visibility (no interaction-resets here — long-press
        // menus tend to be tap-once-and-go).
        if (sState.trackContextSheetHideSec > 0) {
            kotlinx.coroutines.delay(sState.trackContextSheetHideSec * 1000L)
            onDismiss()
        }
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
            // Track header
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))

            actions.onFavourite?.let {
                Item(Icons.Filled.StarBorder, "Favourite", onClick = it)
            }
            actions.onUnfavourite?.let {
                Item(Icons.Filled.Star, "Unfavourite", onClick = it, tint = TealAccent)
            }
            actions.onHide?.let {
                Item(Icons.Filled.VisibilityOff, "Hide", onClick = it)
            }
            actions.onAddToQueueNext?.let {
                Item(Icons.Filled.PlaylistAdd, "Add to queue next", onClick = it)
            }
            actions.onEditTags?.let {
                Item(Icons.Filled.Edit, "Edit tags", onClick = it)
            }
            actions.onOverrideSpeed?.let {
                Item(Icons.Filled.Speed, "Override speed", onClick = it)
            }
            actions.onOverrideAudio?.let {
                Item(Icons.Filled.Headset, "Override audio effects", onClick = it)
            }
            actions.onOverrideVideo?.let {
                Item(Icons.Filled.VideocamOff, "Override video effects", onClick = it)
            }
            actions.onShare?.let {
                Item(Icons.Filled.Share, "Share", onClick = it)
            }
            actions.onOpenInOtherApp?.let {
                Item(Icons.Filled.OpenInNew, "Open in other app", onClick = it)
            }
            actions.onSaveOffline?.let {
                Item(Icons.Filled.Headset, "Save offline copy", onClick = it)
            }
            actions.onRemoveOffline?.let {
                Item(Icons.Filled.Headset, "Remove offline copy", onClick = it, tint = ErrorRed)
            }
            actions.onDelete?.let {
                Item(Icons.Filled.Delete, "Delete", onClick = it, tint = ErrorRed)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Item(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}
