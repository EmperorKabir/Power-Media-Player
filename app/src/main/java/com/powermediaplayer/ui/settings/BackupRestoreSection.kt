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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    private val drive: DriveOAuthProvider,
    private val settings: com.powermediaplayer.data.preferences.SettingsDataStore
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** P2.5 — the display name of the Drive folder backups are written to
     *  ("" = My Drive root). */
    val backupFolderName: StateFlow<String> = settings.driveBackupFolderName
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "")

    fun setBackupFolder(id: String, name: String) {
        viewModelScope.launch { settings.setDriveBackupFolder(id, name) }
    }

    /** P2.5 folder picker — sub-folders of [parentId] (reuses the Drive browser). */
    suspend fun listBackupFolders(parentId: String) = drive.listSubFolders(parentId)

    fun suggestedFileName(): String = "PowerMediaPlayer-backup-${stamp("yyyyMMdd-HHmmss")}.json"

    fun exportToLocal(uri: Uri) = launchBusy("Backup saved.", "Backup failed") {
        val json = backupManager.buildBackupJson(stampIso())
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: error("Could not open the chosen file")
        }
        0
    }

    fun importFromLocal(uri: Uri) = launchBusy("Restored", "Restore failed", restore = true) {
        val json = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("Could not read the chosen file")
        }
        backupManager.restoreFromJson(json).getOrThrow()
    }

    fun backupToDrive() {
        _busy.value = true
        viewModelScope.launch {
            val r = runCatching {
                val json = backupManager.buildBackupJson(stampIso())
                val folderId = settings.driveBackupFolderId.first().ifBlank { "root" }
                // Overwrite the existing backup file if present, else create one in the
                // chosen folder — keeps a single restorable copy, no duplicates.
                val existing = drive.findNewestFileByName(DRIVE_BACKUP_NAME)
                val id = if (existing != null) {
                    drive.updateTextFile(existing, json).getOrThrow(); existing
                } else {
                    drive.uploadTextFile(DRIVE_BACKUP_NAME, json, parentFolderId = folderId).getOrThrow()
                }
                // Relocate into the chosen folder if needed (best-effort; drive.file can
                // refuse a move into an arbitrary user folder).
                if (folderId != "root") drive.moveFileToFolder(id, folderId) else true
            }
            _status.value = when {
                r.isFailure -> "Drive backup failed: ${r.exceptionOrNull()?.message}"
                r.getOrNull() == false ->
                    "Backed up to Drive (in My Drive root — Google wouldn't let the app move it into that folder)."
                else -> "Backed up to Drive."
            }
            _busy.value = false
        }
    }

    fun restoreFromDrive() = launchBusy("Restored", "Drive restore failed", restore = true) {
        // Gated on sign-in by the UI, so reaching here means an account is attached and
        // "no backup" is truthful (was previously indistinguishable from "not signed in").
        val id = drive.findNewestFileByName(DRIVE_BACKUP_NAME)
            ?: error("No backup file found in your Google Drive yet")
        val json = drive.downloadTextFile(id).getOrThrow()
        backupManager.restoreFromJson(json).getOrThrow()
    }

    /** P2 — delete the app's backup file from Google Drive (user-initiated, confirmed
     *  in the UI). */
    fun deleteDriveBackup() {
        _busy.value = true
        viewModelScope.launch {
            val r = runCatching {
                val id = drive.findNewestFileByName(DRIVE_BACKUP_NAME)
                    ?: error("No backup file found in your Google Drive")
                drive.deleteFile(id).getOrThrow()
            }
            _status.value = if (r.isSuccess) "Deleted the Drive backup."
                else "Delete failed: ${r.exceptionOrNull()?.message}"
            _busy.value = false
        }
    }

    // ── Drive sign-in gating (P2) — the backup/restore buttons must trigger the SAME
    // Google sign-in the Cloud tab uses when no account is attached (a fresh install
    // otherwise failed with a misleading "no backup found"). Shares the singleton
    // DriveOAuthProvider, so signing in here also readies the Cloud tab. ──
    fun isDriveSignedIn(): Boolean = drive.currentAccountEmail() != null
    fun buildDriveSignInIntent(): android.content.Intent = drive.buildSignInIntent()
    /** Handle the sign-in result; on success run [onSignedIn] (the queued backup/restore). */
    fun completeDriveSignIn(data: android.content.Intent?, onSignedIn: () -> Unit) {
        viewModelScope.launch {
            if (drive.handleSignInResult(data).isSuccess) onSignedIn()
            else _status.value = "Google sign-in was cancelled or failed"
        }
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

    // P2: a queued Drive action (backup or restore) to run once sign-in completes, and
    // the Google sign-in launcher itself. When no account is attached, the Drive buttons
    // launch sign-in first (mirroring the Cloud tab) then auto-run the action.
    var pendingDriveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showBackupFolderPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val backupFolderName by vm.backupFolderName.collectAsStateWithLifecycle()
    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.completeDriveSignIn(result.data) {
            pendingDriveAction?.invoke(); pendingDriveAction = null
        }
    }
    fun runDriveOrSignIn(action: () -> Unit) {
        if (vm.isDriveSignedIn()) action()
        else { pendingDriveAction = action; driveSignInLauncher.launch(vm.buildDriveSignInIntent()) }
    }

    if (showBackupFolderPicker) {
        com.powermediaplayer.ui.cloud.DriveFolderBrowser(
            listSubFolders = { pid -> vm.listBackupFolders(pid) },
            onAdd = { id, name -> vm.setBackupFolder(id, name); showBackupFolderPicker = false },
            onDismiss = { showBackupFolderPicker = false },
            addLabel = "Save backups here"
        )
    }
    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Drive backup?") },
            text = {
                Text(
                    "This permanently removes the Power Media Player backup file from your " +
                        "Google Drive. Your local settings are not affected."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteConfirm = false
                    if (!busy) runDriveOrSignIn { vm.deleteDriveBackup() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDeleteConfirm = false }
                ) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            text = "Save everything except your actual media files, settings, per-file " +
                "effects, equaliser presets, favourites, play history, bookmarks, playlists, " +
                "podcast subscriptions and all metadata, to a file or to Google Drive, and " +
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
                onClick = { if (!busy) runDriveOrSignIn { vm.backupToDrive() } },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Back up to Drive") }
            OutlinedButton(
                onClick = { if (!busy) runDriveOrSignIn { vm.restoreFromDrive() } },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Restore from Drive") }
        }
        // P2.5 — where in Drive the backup is stored (default My Drive root). "Change"
        // opens the same native folder browser the Cloud tab uses.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = "Drive backup folder: " +
                    backupFolderName.ifBlank { "My Drive (default)" },
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.TextButton(
                onClick = { if (!busy) runDriveOrSignIn { showBackupFolderPicker = true } },
                enabled = !busy
            ) { Text("Change") }
            if (backupFolderName.isNotBlank()) {
                androidx.compose.material3.TextButton(
                    onClick = { if (!busy) vm.setBackupFolder("", "") },
                    enabled = !busy
                ) { Text("Reset") }
            }
        }
        // P2 — remove the backup file from Drive (confirmed).
        androidx.compose.material3.TextButton(
            onClick = { if (!busy) showDeleteConfirm = true },
            enabled = !busy
        ) {
            Text("Delete Drive backup", color = MaterialTheme.colorScheme.error)
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
