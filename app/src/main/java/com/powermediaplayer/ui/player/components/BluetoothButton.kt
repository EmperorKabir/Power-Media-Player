package com.powermediaplayer.ui.player.components

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.settings.SettingsViewModel
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.BluetoothDeviceInfo
import com.powermediaplayer.util.BluetoothHelper

/**
 * Bluetooth status button for the main controls. Tapping opens a
 * bottom sheet that:
 *   - Shows enable/disable toggle (delegates to system intent)
 *   - Lists paired audio devices with a "currently connected" badge
 *   - Offers a shortcut to system Bluetooth settings (pairing /
 *     disconnect actions live there — Android does not expose them
 *     to third-party apps)
 *
 * No app-side connection initiation is attempted because the public
 * BluetoothA2dp.connect API has been hidden since SDK 28.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothButton(
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by settingsVm.uiState.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }
    var enabledState by remember { mutableStateOf(BluetoothHelper.isEnabled(context)) }
    var permissionGranted by remember { mutableStateOf(BluetoothHelper.hasConnectPermission(context)) }
    // Tri-state: off / on-but-no-A2DP-route / A2DP-routing-active.
    // A2DP-active is the true "playing through Bluetooth" state.
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    var a2dpActive by remember { mutableStateOf(audioManager.isBluetoothA2dpOn) }
    // Audit 3.11 — event-driven instead of a 2s binder poll: routing
    // adds/removes fire AudioDeviceCallback the moment a BT sink
    // connects or drops.
    androidx.compose.runtime.DisposableEffect(enabledState) {
        val cb = object : android.media.AudioDeviceCallback() {
            private fun refresh() {
                a2dpActive = enabledState && audioManager.isBluetoothA2dpOn
            }
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = refresh()
        }
        a2dpActive = enabledState && audioManager.isBluetoothA2dpOn
        audioManager.registerAudioDeviceCallback(cb, null)
        onDispose { runCatching { audioManager.unregisterAudioDeviceCallback(cb) } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) enabledState = BluetoothHelper.isEnabled(context)
    }

    val enableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        enabledState = BluetoothHelper.isEnabled(context)
    }

    IconButton(
        onClick = {
            // Tap ALWAYS opens the sheet (the disconnect/use-phone-speaker
            // action lives INSIDE it now — tap-to-disconnect hid the menu).
            if (!permissionGranted) {
                permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            sheetOpen = true
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = when {
                a2dpActive -> Icons.Filled.BluetoothConnected
                enabledState -> Icons.Filled.Bluetooth
                else -> Icons.Filled.BluetoothDisabled
            },
            contentDescription = when {
                a2dpActive -> "Bluetooth audio active"
                enabledState -> "Bluetooth on, not routing audio"
                else -> "Bluetooth off"
            },
            tint = when {
                a2dpActive -> TealAccent
                enabledState -> TealAccent.copy(alpha = 0.7f)
                else -> TextSecondary
            }
        )
    }

    if (sheetOpen) {
        PopupOpenGuard()
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = SurfaceElevated
        ) {
            BluetoothSheetContent(
                isEnabled = enabledState,
                hasPermission = permissionGranted,
                a2dpActive = a2dpActive,
                onDisconnect = {
                    // "Disconnect" without turning Bluetooth off = reroute this
                    // app's audio to the phone speaker (a true ACL disconnect
                    // needs privileged APIs). BT stays on; reconnects on next
                    // route change / when BT is re-selected.
                    val ok = com.powermediaplayer.service.PlaybackService
                        .rerouteAudioToPhoneSpeaker(context)
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Audio moved to phone speaker — Bluetooth still on"
                        else "Couldn't switch audio output",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    sheetOpen = false
                },
                offsetMs = settings.btVideoAudioOffsetMs,
                onOffsetChange = { settingsVm.setBtVideoAudioOffsetMs(it) },
                onRequestPermission = {
                    permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                },
                onEnable = { enableLauncher.launch(BluetoothHelper.enableIntent()) },
                onOpenSettings = {
                    context.startActivity(
                        BluetoothHelper.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
    }
}

@Composable
private fun BluetoothSheetContent(
    isEnabled: Boolean,
    hasPermission: Boolean,
    a2dpActive: Boolean,
    onDisconnect: () -> Unit,
    offsetMs: Int,
    onOffsetChange: (Int) -> Unit,
    onRequestPermission: () -> Unit,
    onEnable: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var bonded by remember { mutableStateOf<List<BluetoothDeviceInfo>>(emptyList()) }
    var connectedAddrs by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(isEnabled, hasPermission) {
        if (isEnabled && hasPermission) {
            bonded = BluetoothHelper.bondedDevices(context)
            BluetoothHelper.connectedAudioDevices(context) { conn ->
                connectedAddrs = conn.map { it.address }.toSet()
            }
        } else {
            bonded = emptyList()
            connectedAddrs = emptySet()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)               // foldable folded landscape safe
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Bluetooth",
            style = MaterialTheme.typography.titleLarge,
            color = TealAccent
        )
        Spacer(Modifier.height(12.dp))

        // ── Disconnect (play on phone speaker) ───────────────────
        // Shown only while BT audio is actually routing. Moves THIS app's
        // audio back to the phone speaker; Bluetooth stays on (a true ACL
        // disconnect needs privileged APIs). This replaces the old
        // tap-the-button-to-disconnect, which hid this sheet.
        if (a2dpActive) {
            FilledTonalButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.BluetoothDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Play on phone speaker (stop Bluetooth audio)")
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Video / audio sync offset ────────────────────────────
        // Same stored value as Settings → "Bluetooth video audio offset",
        // so adjusting it here moves the Settings slider and vice-versa.
        // Shown regardless of BT permission — it's a playback tuning.
        AvSyncOffsetControl(
            title = "Video / audio sync offset",
            description = "Bluetooth adds audio latency, so lip-sync can " +
                "drift when watching video over a BT speaker / headphones. " +
                "Slide right to delay the video to match. Range ±1 second.",
            offsetMs = offsetMs,
            onOffsetChange = onOffsetChange
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = DisabledContent)
        Spacer(Modifier.height(12.dp))

        if (!hasPermission) {
            FilledTonalButton(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant Bluetooth permission") }
            return@Column
        }

        // ── Enable toggle + settings link ────────────────────────
        // Switch acts as on/off: ON path uses ACTION_REQUEST_ENABLE
        // (system consent dialog). OFF path opens system Bluetooth
        // settings — Android removed the public BluetoothAdapter.disable()
        // API for SDK 33+ apps, so this is the cleanest path.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEnabled) "Bluetooth on" else "Bluetooth off",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = { wantOn ->
                    if (wantOn) onEnable() else onOpenSettings()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TealAccent,
                    checkedTrackColor = Teal800,
                    uncheckedThumbColor = DisabledGrey,
                    uncheckedTrackColor = SurfaceElevated
                )
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = "Settings",
                    color = TealAccent,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = DisabledContent)
        Spacer(Modifier.height(8.dp))

        // ── Devices ──────────────────────────────────────────────
        Text(
            text = "Paired devices",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(4.dp))

        when {
            !isEnabled -> Text(
                "Turn on Bluetooth to see your devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )
            bonded.isEmpty() -> Text(
                "No paired devices. Use system settings to pair a new device.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )
            else -> bonded.forEach { dev ->
                BluetoothDeviceRow(
                    device = dev.copy(isConnected = dev.address in connectedAddrs),
                    onClick = onOpenSettings
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pair a new device", color = TealAccent)
        }
    }
}

@Composable
private fun BluetoothDeviceRow(device: BluetoothDeviceInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (device.isAudio) Icons.Filled.Headset else Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = if (device.isConnected) TealAccent else TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (device.isConnected) TealAccent else TextPrimary
            )
            Text(
                text = if (device.isConnected) "Connected" else device.address,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
        if (device.isConnected) {
            Surface(
                color = Teal800,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = TealAccent,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}
