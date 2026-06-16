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
    // True while THIS app's audio is force-pinned to the phone speaker
    // (reroute override). Lets the sheet flip between "play on phone" and
    // "play on Bluetooth", so a stuck reroute is always recoverable even when
    // the BT device was already connected.
    val rerouteActive by com.powermediaplayer.service.PlaybackService
        .audioRerouteActiveFlow.collectAsStateWithLifecycle()
    // True while the app is casting: BT may stay system-connected but it is
    // NOT the app's active output, so the button must not show "routing to BT".
    val castActive by com.powermediaplayer.service.PlaybackService
        .castActiveFlow.collectAsStateWithLifecycle()
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

    // BT is the app's ACTIVE output only when A2DP is routing AND we're not
    // casting (a cast moves the app's audio to the cast device even though BT
    // can stay system-connected).
    val btRouting = a2dpActive && !castActive

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
                btRouting -> Icons.Filled.BluetoothConnected
                enabledState -> Icons.Filled.Bluetooth
                else -> Icons.Filled.BluetoothDisabled
            },
            contentDescription = when {
                btRouting && !rerouteActive -> "Bluetooth audio active"
                btRouting && rerouteActive -> "Bluetooth connected, audio on phone speaker"
                castActive && a2dpActive -> "Bluetooth connected, audio on cast device"
                enabledState -> "Bluetooth on, not routing audio"
                else -> "Bluetooth off"
            },
            tint = when {
                // Connected AND routing BT audio → full accent.
                btRouting && !rerouteActive -> TealAccent
                // Connected but audio pinned to the phone speaker → dimmed,
                // so the button reflects "playing out the phone" at a glance.
                btRouting && rerouteActive -> TealAccent.copy(alpha = 0.5f)
                // BT on but not the app output (casting, or no media route) →
                // dimmed "on, not routing".
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
                a2dpActive = btRouting,
                castActive = castActive && a2dpActive,
                rerouteActive = rerouteActive,
                onPlayOnPhone = {
                    // "Disconnect" without turning Bluetooth off = reroute this
                    // app's audio to the phone speaker (a true ACL disconnect
                    // needs privileged APIs). BT stays on; recover via
                    // onPlayOnBluetooth below.
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
                onPlayOnBluetooth = {
                    // Undo the reroute → default routing flows back to the
                    // connected Bluetooth device. Fixes "connected to <device>
                    // but playing out the phone".
                    com.powermediaplayer.service.PlaybackService.clearAudioReroute()
                    android.widget.Toast.makeText(
                        context,
                        "Audio moved back to Bluetooth",
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
    castActive: Boolean,
    rerouteActive: Boolean,
    onPlayOnPhone: () -> Unit,
    onPlayOnBluetooth: () -> Unit,
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

        // ── Audio-output toggle (reversible) ─────────────────────
        // Shown whenever a BT device is connected (a2dpActive) OR the app has
        // pinned audio to the phone speaker (rerouteActive). A true ACL
        // disconnect needs privileged APIs, so "disconnect" = pin this app's
        // audio to the phone speaker; the toggle flips back to the BT device.
        // This is the fix for "connected to <device> but playing out the phone":
        // the override is now always recoverable from here.
        if (castActive) {
            // While casting, the app's audio is on the cast device — the BT
            // reroute toggle (which acts on the local player) is moot. Make the
            // state explicit instead of silently showing nothing.
            Text(
                text = "Casting — audio is playing on the cast device. " +
                    "Bluetooth stays system-connected; stop casting to use it " +
                    "for playback in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(Modifier.height(12.dp))
        } else if (a2dpActive || rerouteActive) {
            if (rerouteActive) {
                Text(
                    text = "Audio is on the phone speaker. Bluetooth is still " +
                        "connected — tap below to play through it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onPlayOnBluetooth,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.BluetoothAudio,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Play on Bluetooth")
                }
            } else {
                FilledTonalButton(
                    onClick = onPlayOnPhone,
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
                "Slide right to delay the video to match. Range 0–1 second.",
            offsetMs = offsetMs,
            onOffsetChange = onOffsetChange,
            range = 0f..1000f
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
