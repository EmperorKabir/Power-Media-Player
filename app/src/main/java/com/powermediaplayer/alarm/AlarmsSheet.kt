package com.powermediaplayer.alarm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §C12 — alarms list + add/edit. Surfaced as a ModalBottomSheet from
 * Settings → Alarms entry. Each row shows time / days / enable switch
 * / delete. Tap the floating "+" to add a new alarm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsSheet(
    settingsDataStore: SettingsDataStore,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val alarms by settingsDataStore.scheduledAlarms.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<AlarmRecord?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // §C12 — full-screen-intent permission gate banner. Android 14+
            // requires explicit user approval before an app can launch a
            // full-screen activity from a notification. Without this the
            // alarm would still post a heads-up notification but the
            // FullScreenAlarmActivity wouldn't auto-launch on lockscreen.
            FullScreenIntentBanner()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alarms", style = MaterialTheme.typography.titleMedium, color = TealAccent)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    editing = AlarmRecord(
                        id = System.currentTimeMillis(),
                        hour = 7, minute = 0, days = 0,
                        mediaUri = "", enabled = true
                    )
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add alarm", tint = TealAccent)
                }
            }
            Text(
                "Wake to a chosen track. Falls back to whatever the player has loaded if media URI is left blank.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(Modifier.height(8.dp))
            if (alarms.isEmpty()) {
                Text(
                    "No alarms yet. Tap + to add one.",
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(alarms, key = { it.id }) { alarm -> AlarmRow(
                        alarm = alarm,
                        onToggle = {
                            scope.launch {
                                val updated = alarm.copy(enabled = it)
                                withContext(Dispatchers.IO) {
                                    settingsDataStore.upsertAlarm(updated)
                                }
                                if (it) AlarmScheduler.schedule(context, updated)
                                else AlarmScheduler.cancel(context, alarm.id)
                            }
                        },
                        onEdit = { editing = alarm },
                        onDelete = {
                            scope.launch {
                                AlarmScheduler.cancel(context, alarm.id)
                                withContext(Dispatchers.IO) { settingsDataStore.deleteAlarm(alarm.id) }
                            }
                        }
                    ) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { current ->
        AlarmEditor(
            initial = current,
            onCancel = { editing = null },
            onSave = { updated ->
                scope.launch {
                    withContext(Dispatchers.IO) { settingsDataStore.upsertAlarm(updated) }
                    AlarmScheduler.schedule(context, updated)
                }
                editing = null
            }
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: AlarmRecord,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(alarm.timeLabel, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text(alarm.daysLabel, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            if (alarm.mediaUri.isNotBlank()) {
                Text(
                    text = alarm.mediaUri.substringAfterLast('/').take(40),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Delete", tint = ErrorRed)
        }
    }
}

@Composable
private fun FullScreenIntentBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nm = context.getSystemService(android.app.NotificationManager::class.java)
    val canFsi = remember {
        if (android.os.Build.VERSION.SDK_INT >= 34 && nm != null)
            runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
        else true
    }
    if (canFsi) return
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color(0x33FFA000),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Full-screen alarm permission needed",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    "Android 14+ requires you to explicitly grant " +
                        "full-screen alarm rights. Without it, alarms " +
                        "still ring but can't draw over the lock-screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            androidx.compose.material3.TextButton(onClick = {
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }) { Text("Grant", color = TealAccent) }
        }
    }
}

@Composable
private fun AlarmEditor(
    initial: AlarmRecord,
    onCancel: () -> Unit,
    onSave: (AlarmRecord) -> Unit
) {
    var hour by remember { mutableStateOf(initial.hour) }
    var minute by remember { mutableStateOf(initial.minute) }
    var days by remember { mutableStateOf(initial.days) }
    var mediaUri by remember { mutableStateOf(initial.mediaUri) }
    var displayLabel by remember { mutableStateOf(initial.displayLabel) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Edit alarm", color = TealAccent) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Time:", color = TextSecondary, modifier = Modifier.padding(end = 8.dp))
                    OutlinedTextField(
                        value = hour.toString().padStart(2, '0'),
                        onValueChange = { hour = it.toIntOrNull()?.coerceIn(0, 23) ?: 0 },
                        label = { Text("hh") },
                        singleLine = true,
                        modifier = Modifier.size(width = 70.dp, height = 56.dp)
                    )
                    Text(":", color = TextPrimary, modifier = Modifier.padding(horizontal = 4.dp))
                    OutlinedTextField(
                        value = minute.toString().padStart(2, '0'),
                        onValueChange = { minute = it.toIntOrNull()?.coerceIn(0, 59) ?: 0 },
                        label = { Text("mm") },
                        singleLine = true,
                        modifier = Modifier.size(width = 70.dp, height = 56.dp)
                    )
                }
                Text("Days", color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("M" to 1, "T" to 2, "W" to 4, "T" to 8, "F" to 16, "S" to 32, "S" to 64).forEach { (label, bit) ->
                        FilterChip(
                            selected = (days and bit) != 0,
                            onClick = { days = days xor bit },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    if (days == 0) "One-shot" else "Recurring",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                var showPicker by remember { mutableStateOf(false) }
                val pickedLabel = displayLabel
                Text(
                    "Sound",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = if (pickedLabel.isNotBlank()) pickedLabel
                        else mediaUri.substringAfterLast('/'),
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Track") },
                        placeholder = { Text("Tap Pick to choose") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { showPicker = true }
                    ) { Text("Pick", color = TealAccent) }
                }
                Text(
                    if (mediaUri.isBlank())
                        "Leave empty to resume the last track that was playing."
                    else "Picked: ${pickedLabel.ifBlank { mediaUri.substringAfterLast('/') }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                if (showPicker) {
                    AlarmMediaPickerSheet(
                        onPicked = { uri, label ->
                            mediaUri = uri
                            displayLabel = label
                            showPicker = false
                        },
                        onDismiss = { showPicker = false }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(
                    hour = hour, minute = minute, days = days,
                    mediaUri = mediaUri, displayLabel = displayLabel
                ))
            }) { Text("Save", color = TealAccent) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = OledBlack
    )
}
