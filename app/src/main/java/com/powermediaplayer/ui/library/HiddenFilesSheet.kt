package com.powermediaplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary

/**
 * §C27: Hidden files Settings sub-screen, surfaced as a bottom sheet.
 * Lists every URI the user has hidden via the long-press "Hide" action;
 * each row has an Unhide button. "Unhide all" button at the bottom for
 * the convenience of restoring everything in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenFilesSheet(
    hiddenUris: Set<String>,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit
) {
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
                text = "Hidden files (${hiddenUris.size})",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent
            )
            Text(
                text = "These files are hidden from the Library list. " +
                    "They are still on your phone — only the in-app filter is affected.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(Modifier.height(12.dp))

            if (hiddenUris.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nothing is hidden yet.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(hiddenUris.toList(), key = { it }) { uri ->
                        HiddenRow(uri = uri, onUnhide = { onUnhide(uri) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onUnhideAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unhide all (${hiddenUris.size})", color = TealAccent)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HiddenRow(uri: String, onUnhide: () -> Unit) {
    // Display the file's last path segment as a friendly label —
    // full URIs are noisy. Falls back to the URI itself if the
    // last segment is empty.
    val label = uri.substringAfterLast('/').ifBlank { uri }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = uri,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onUnhide) {
            Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = "Unhide",
                tint = TealAccent
            )
        }
    }
}
