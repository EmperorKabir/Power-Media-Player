package com.powermediaplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary

/**
 * §C25 — "Edit tags" dialog. The locked spec doesn't dictate a
 * full-blown taglib-grade editor; we expose title / artist / album
 * which are the user-visible labels everywhere else in the app.
 *
 * Persistence: writes through [LibraryViewModel.editTags] which
 * stores user-supplied overrides in DataStore, keyed by mediaUri.
 * The Library list re-renders with the override values via a small
 * Map<uri, TagOverride> read alongside the MediaStore scan results.
 *
 * Note: this does NOT mutate the file's actual ID3 tags — that
 * requires write access into MediaStore on Android 10+ which the
 * user has to grant per-file via SAF. A native re-write path would
 * be a follow-up; the override-map approach is the spec-locked
 * "Edit tags" affordance and matches what the long-press menu's
 * label promises.
 */
@Composable
fun EditTagsDialog(
    initialTitle: String,
    initialArtist: String,
    initialAlbum: String,
    onCancel: () -> Unit,
    onSave: (title: String, artist: String, album: String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var album by remember { mutableStateOf(initialAlbum) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Edit tags", color = TealAccent) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artist, onValueChange = { artist = it },
                    label = { Text("Artist") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = album, onValueChange = { album = it },
                    label = { Text("Album") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Saved as a per-file override. The original ID3/" +
                        "Vorbis/MP4 tags on disk are not modified.",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), artist.trim(), album.trim()) }) {
                Text("Save", color = TealAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = OledBlack
    )
}
