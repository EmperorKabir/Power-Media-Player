package com.powermediaplayer.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Part 4.3 — global storage-location pickers (one default podcast folder +
 * one Drive-files folder) plus the entry to the unified Downloads manager.
 * Self-contained VM so the large SettingsViewModel combine is untouched.
 */
@HiltViewModel
class StorageSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settings: SettingsDataStore
) : ViewModel() {
    val podcastFolder: StateFlow<String> = settings.podcastDownloadTreeUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val driveFolder: StateFlow<String> = settings.driveOfflineTreeUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setPodcastFolder(uri: Uri?) = viewModelScope.launch {
        if (uri != null) com.powermediaplayer.util.SafStorage.persistTree(appContext, uri)
        settings.setPodcastDownloadTreeUri(uri?.toString())
    }
    fun setDriveFolder(uri: Uri?) = viewModelScope.launch {
        if (uri != null) com.powermediaplayer.util.SafStorage.persistTree(appContext, uri)
        settings.setDriveOfflineTreeUri(uri?.toString())
    }
}

@Composable
fun StorageFoldersRow(
    onOpenDownloads: () -> Unit,
    vm: StorageSettingsViewModel = hiltViewModel()
) {
    val podcast by vm.podcastFolder.collectAsStateWithLifecycle()
    val drive by vm.driveFolder.collectAsStateWithLifecycle()
    val podcastPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.setPodcastFolder(uri) }
    val drivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.setDriveFolder(uri) }

    Column(Modifier.fillMaxWidth()) {
        FolderRow(
            label = "Podcast download folder",
            value = folderLabel(podcast),
            onChange = { podcastPicker.launch(com.powermediaplayer.util.SafStorage.phoneStorageInitialUri()) },
            onReset = if (podcast.isNotBlank()) ({ vm.setPodcastFolder(null) }) else null
        )
        FolderRow(
            label = "Google Drive files folder",
            value = folderLabel(drive),
            onChange = { drivePicker.launch(com.powermediaplayer.util.SafStorage.phoneStorageInitialUri()) },
            onReset = if (drive.isNotBlank()) ({ vm.setDriveFolder(null) }) else null
        )
        Text(
            "Spotify tracks can't be saved to your phone, Spotify only allows them to be streamed, so there's no folder to choose for them.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenDownloads() }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = TealAccent)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Manage downloads", color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall)
                Text("Podcasts + offline Drive files · storage usage",
                    color = TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
private fun FolderRow(
    label: String,
    value: String,
    onChange: () -> Unit,
    onReset: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = TextSecondary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onChange) { Text("Change", color = TealAccent) }
        if (onReset != null) {
            TextButton(onClick = onReset) { Text("Reset", color = TextTertiary) }
        }
    }
}

private fun folderLabel(uri: String): String =
    if (uri.isBlank()) "Default (app storage)"
    else com.powermediaplayer.util.SafStorage.treeLabel(Uri.parse(uri))
