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
import androidx.compose.foundation.layout.widthIn
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
        Column(modifier = Modifier
            .widthIn(max = 560.dp)   // audit 8.1 (F5) — no full-width tablet sheet
            .align(Alignment.CenterHorizontally)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)) {
            // §C12 — full-screen-intent permission banner. Locked spec
            // says it appears on the FIRST alarm save. We surface it
            // when alarms.isNotEmpty() (post-save) AND the permission
            // is missing.
            // 2026-07-22: exact-alarm permission banner (Android 12+). Shown
            // whenever the grant is missing — on Android 11 and older
            // canScheduleExact() is always true so this never appears.
            ExactAlarmBanner()
            if (alarms.isNotEmpty()) FullScreenIntentBanner()
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

/** Reusable int input. Kept compact so the editor stays scrollable. */
@Composable
private fun IntField(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value.toString(),
            onValueChange = {
                val parsed = it.toIntOrNull()
                if (parsed != null) onChange(parsed.coerceIn(range))
                else if (it.isBlank()) onChange(range.first)
            },
            singleLine = true,
            modifier = Modifier.size(width = 96.dp, height = 56.dp)
        )
    }
}

@Composable
private fun ExactAlarmBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-evaluate whenever the sheet recomposes (e.g. returning from Settings
    // after a grant) — cheap OS query, no caching so it clears once granted.
    if (AlarmScheduler.canScheduleExact(context)) return
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
                    "Alarm permission needed",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    "Android 14+ needs your permission to ring alarms at the " +
                        "exact time. Without it your saved alarms will not go " +
                        "off. Tap Grant, then turn on Alarms and reminders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            androidx.compose.material3.TextButton(onClick = {
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.onFailure {
                    // Fallback: some OEMs don't honour the direct action —
                    // open the app's settings page instead.
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }) { Text("Grant", color = TealAccent) }
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
    // §C12 LOCKED — full editor. Each field gets a sensible default
    // from [AlarmRecord]; we only persist on Save.
    var startVolumePct by remember { mutableStateOf(initial.startVolumePct) }
    var endVolumePct by remember { mutableStateOf(initial.endVolumePct) }
    var rampSeconds by remember { mutableStateOf(initial.rampSeconds) }
    var holdMinutes by remember { mutableStateOf(initial.holdMinutes) }
    var windDownSeconds by remember { mutableStateOf(initial.windDownSeconds) }
    var snoozeEnabled by remember { mutableStateOf(initial.snoozeEnabled) }
    var snoozeMinutes by remember { mutableStateOf(initial.snoozeMinutes) }
    var maxSnoozes by remember { mutableStateOf(initial.maxSnoozes) }
    var snoozeRestartFromStart by remember { mutableStateOf(initial.snoozeRestartFromStart) }
    var skipNextCount by remember { mutableStateOf(initial.skipNextCount) }
    var stopMethod by remember { mutableStateOf(initial.stopMethod) }
    var vibration by remember { mutableStateOf(initial.vibration) }

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

                // §C12 — Audio: ramp + hold + wind-down.
                androidx.compose.material3.HorizontalDivider(
                    color = TextTertiary, modifier = Modifier.padding(vertical = 4.dp)
                )
                Text("Volume ramp", color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium)
                IntField("Start volume %", startVolumePct, 0..100) { startVolumePct = it }
                IntField("End volume %", endVolumePct, 0..100) { endVolumePct = it }
                IntField("Ramp duration (s)", rampSeconds, 0..600) { rampSeconds = it }
                IntField("Hold (min, -1 = indefinite)", holdMinutes, -1..600) { holdMinutes = it }
                IntField("Wind-down (s, 0 = abrupt cut)", windDownSeconds, 0..600) { windDownSeconds = it }

                // §C12 — Snooze.
                androidx.compose.material3.HorizontalDivider(
                    color = TextTertiary, modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = snoozeEnabled,
                        onCheckedChange = { snoozeEnabled = it })
                    Text(" Snooze enabled",
                        color = if (snoozeEnabled) TextPrimary else TextTertiary,
                        modifier = Modifier.padding(top = 12.dp))
                }
                if (snoozeEnabled) {
                    IntField("Snooze duration (min)", snoozeMinutes, 1..60) { snoozeMinutes = it }
                    IntField("Max snoozes (-1 = unlimited)", maxSnoozes, -1..20) { maxSnoozes = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = snoozeRestartFromStart,
                            onCheckedChange = { snoozeRestartFromStart = it })
                        Text(" Snooze restarts ramp from start",
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }

                // §C12 — Skip next N alarms.
                IntField("Skip next N alarms", skipNextCount, 0..7) { skipNextCount = it }

                // §C12 — Stop method + vibration.
                androidx.compose.material3.HorizontalDivider(
                    color = TextTertiary, modifier = Modifier.padding(vertical = 4.dp)
                )
                Text("Stop method", color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AlarmRecord.StopMethod.values().forEach { sm ->
                        FilterChip(
                            selected = stopMethod == sm,
                            onClick = { stopMethod = sm },
                            label = { Text(sm.name) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vibration,
                        onCheckedChange = { vibration = it })
                    Text(" Vibrate while ringing",
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(
                    hour = hour, minute = minute, days = days,
                    mediaUri = mediaUri, displayLabel = displayLabel,
                    startVolumePct = startVolumePct,
                    endVolumePct = endVolumePct,
                    rampSeconds = rampSeconds,
                    holdMinutes = holdMinutes,
                    windDownSeconds = windDownSeconds,
                    snoozeEnabled = snoozeEnabled,
                    snoozeMinutes = snoozeMinutes,
                    maxSnoozes = maxSnoozes,
                    snoozeRestartFromStart = snoozeRestartFromStart,
                    skipNextCount = skipNextCount,
                    stopMethod = stopMethod,
                    vibration = vibration
                ))
            }) { Text("Save", color = TealAccent) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = OledBlack
    )
}
