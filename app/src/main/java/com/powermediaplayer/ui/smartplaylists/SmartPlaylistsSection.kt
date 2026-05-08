package com.powermediaplayer.ui.smartplaylists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.db.dao.SmartPlaylistDao
import com.powermediaplayer.data.db.entity.SmartPlaylistEntity
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * §C6 — settings entry for managing smart playlists. Editor is a
 * minimal name + raw-JSON-rules input; the resolver
 * (`SmartPlaylistResolver`) accepts any rule shape so power users
 * can hand-craft. A guided form-based editor is the next iteration.
 */
@HiltViewModel
class SmartPlaylistsViewModel @Inject constructor(
    private val dao: SmartPlaylistDao
) : ViewModel() {
    val playlists: StateFlow<List<SmartPlaylistEntity>> =
        dao.getAll().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

    fun upsert(name: String, rulesJson: String) {
        if (name.isBlank() || rulesJson.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            // Cap of 20 per locked spec.
            if (dao.count() >= 20) return@launch
            dao.insert(
                SmartPlaylistEntity(
                    name = name.trim(),
                    rulesJson = rulesJson.trim()
                )
            )
        }
    }

    fun delete(playlist: SmartPlaylistEntity) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(playlist) }
    }
}

@Composable
fun SmartPlaylistsSection(
    vm: SmartPlaylistsViewModel = hiltViewModel()
) {
    val playlists by vm.playlists.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = TealAccent)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Smart playlists (${playlists.size}/20)",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    "Saved-search style: rules combine with AND, sort + " +
                        "limit configurable. Cap 20 per locked spec.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            IconButton(onClick = { showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = TealAccent)
            }
        }
        if (playlists.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            playlists.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall)
                        Text(
                            p.rulesJson.take(80) + if (p.rulesJson.length > 80) "…" else "",
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { vm.delete(p) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Delete",
                            tint = ErrorRed)
                    }
                }
            }
        }
    }

    if (showEditor) {
        SmartPlaylistEditor(
            onCancel = { showEditor = false },
            onSave = { name, json ->
                vm.upsert(name, json)
                showEditor = false
            }
        )
    }
}

@Composable
private fun SmartPlaylistEditor(
    onCancel: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var json by remember {
        mutableStateOf(
            "{\"rules\":[{\"field\":\"isFavourite\",\"op\":\"eq\",\"value\":true}]," +
                "\"sort\":\"lastPlayed\",\"limit\":50}"
        )
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("New smart playlist", color = TealAccent) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it },
                    label = { Text("Rules JSON") },
                    minLines = 4
                )
                Text(
                    "Field options: title, artist, album, duration, " +
                        "playCount, lastPlayedDays, hasBookmark, " +
                        "isFavourite. Ops: equals/contains for strings; " +
                        "eq/lt/lte/gt/gte for numbers; eq for booleans. " +
                        "Sort: name | dateAdded | lastPlayed | playCount " +
                        "| duration | random.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, json) }) {
                Text("Save", color = TealAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = OledBlack
    )
}
