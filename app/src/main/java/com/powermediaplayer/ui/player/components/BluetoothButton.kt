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
fun BluetoothButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var sheetOpen by remember { mutableStateOf(false) }
    var enabledState by remember { mutableStateOf(BluetoothHelper.isEnabled(context)) }
    var permissionGranted by remember { mutableStateOf(BluetoothHelper.hasConnectPermission(context)) }

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
            if (!permissionGranted) {
                permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            sheetOpen = true
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = if (enabledState) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
            contentDescription = "Bluetooth",
            tint = if (enabledState) TealAccent else TextSecondary
        )
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = SurfaceElevated
        ) {
            BluetoothSheetContent(
                isEnabled = enabledState,
                hasPermission = permissionGranted,
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
                color = TextTertiary,
                fontSize = 11.sp
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
