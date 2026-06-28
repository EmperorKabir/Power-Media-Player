package com.powermediaplayer.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.backup.BackupManager
import com.powermediaplayer.cloud.DriveOAuthProvider
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M3 — Backup & restore everything-but-the-media-files (settings, overrides,
 * EQ presets, favourites, history, bookmarks, smart playlists, podcast subs +
 * episode metadata, enrichment cache, offline-copy registry). Local file
 * (SAF — no Drive needed) AND Google Drive.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val drive: DriveOAuthProvider
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun suggestedFileName(): String = "PowerMediaPlayer-backup-${stamp("yyyyMMdd-HHmmss")}.json"

    fun exportToLocal(uri: Uri) = launchBusy("Backup saved.", "Backup failed") {
        val json = backupManager.buildBackupJson(stampIso())
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            ?: error("Could not open the chosen file")
        0
    }

    fun importFromLocal(uri: Uri) = launchBusy("Restored", "Restore failed", restore = true) {
        val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Could not read the chosen file")
        backupManager.restoreFromJson(json).getOrThrow()
    }

    fun backupToDrive() = launchBusy("Backed up to Drive.", "Drive backup failed") {
        val json = backupManager.buildBackupJson(stampIso())
        drive.uploadTextFile(DRIVE_BACKUP_NAME, json).getOrThrow()
        0
    }

    fun restoreFromDrive() = launchBusy("Restored", "Drive restore failed", restore = true) {
        val id = drive.findNewestFileByName(DRIVE_BACKUP_NAME) ?: error("No Drive backup found")
        val json = drive.downloadTextFile(id).getOrThrow()
        backupManager.restoreFromJson(json).getOrThrow()
    }

    fun clearStatus() { _status.value = null }

    private fun launchBusy(
        okMsg: String, failMsg: String, restore: Boolean = false, block: suspend () -> Int
    ) {
        _busy.value = true
        viewModelScope.launch {
            val r = runCatching { block() }
            _status.value = if (r.isSuccess) {
                if (restore) "Restored ${r.getOrNull()} item(s). Restart the app to see all changes."
                else okMsg
            } else "$failMsg: ${r.exceptionOrNull()?.message}"
            _busy.value = false
        }
    }

    private fun stampIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    private fun stamp(fmt: String): String =
        java.text.SimpleDateFormat(fmt, java.util.Locale.US).format(java.util.Date())

    companion object { const val DRIVE_BACKUP_NAME = "PowerMediaPlayer-backup.json" }
}

@Composable
fun BackupRestoreSection(vm: BackupViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportToLocal(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importFromLocal(it) } }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            text = "Save everything except your actual media files — settings, per-file " +
                "effects, equaliser presets, favourites, play history, bookmarks, playlists, " +
                "podcast subscriptions and all metadata — to a file or to Google Drive, and " +
                "restore it on this or another device.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (!busy) exportLauncher.launch(vm.suggestedFileName()) },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Back up to file") }
            OutlinedButton(
                onClick = { if (!busy) importLauncher.launch(arrayOf("application/json")) },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Restore from file") }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { if (!busy) vm.backupToDrive() },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Back up to Drive") }
            OutlinedButton(
                onClick = { if (!busy) vm.restoreFromDrive() },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Restore from Drive") }
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
