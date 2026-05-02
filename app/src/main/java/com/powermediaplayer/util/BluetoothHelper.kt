package com.powermediaplayer.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around BluetoothAdapter for the player UI.
 *
 * - Permission gating: BLUETOOTH_CONNECT (SDK 31+) is required to read
 *   names / bonded list. On older OS the legacy BLUETOOTH permission
 *   is granted at install time so no runtime check is needed.
 * - All public methods short-circuit to safe defaults when the
 *   permission is missing, so callers don't have to re-check.
 */
object BluetoothHelper {

    fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    fun isSupported(context: Context): Boolean = adapter(context) != null

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /**
     * Returns true on SDK ≤30 (permission is install-time) or when
     * BLUETOOTH_CONNECT has been granted at runtime on SDK 31+.
     */
    fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Intent to request the user enable Bluetooth via the system dialog.
     * Caller is responsible for startActivityForResult / launcher.
     */
    fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /**
     * Intent to open the system Bluetooth settings — required for
     * pairing new devices and disabling Bluetooth (no public toggle-off
     * API exists from app context on modern Android).
     */
    fun settingsIntent(): Intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)

    /**
     * List of paired devices. Empty when permission missing or BT off.
     */
    @Suppress("MissingPermission")
    fun bondedDevices(context: Context): List<BluetoothDeviceInfo> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return runCatching {
            adapter.bondedDevices.map { dev ->
                BluetoothDeviceInfo(
                    name = dev.name ?: dev.address,
                    address = dev.address,
                    isAudio = dev.isAudioCapable()
                )
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * Best-effort connection check via the A2DP profile. Returns the
     * names of audio devices currently connected.
     */
    @Suppress("MissingPermission")
    fun connectedAudioDevices(
        context: Context,
        callback: (List<BluetoothDeviceInfo>) -> Unit
    ) {
        if (!hasConnectPermission(context)) {
            callback(emptyList()); return
        }
        val adapter = adapter(context) ?: run {
            callback(emptyList()); return
        }
        if (!adapter.isEnabled) {
            callback(emptyList()); return
        }
        runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val devices = runCatching {
                        proxy.connectedDevices.map { dev ->
                            BluetoothDeviceInfo(
                                name = dev.name ?: dev.address,
                                address = dev.address,
                                isAudio = true,
                                isConnected = true
                            )
                        }
                    }.getOrDefault(emptyList())
                    adapter.closeProfileProxy(profile, proxy)
                    callback(devices)
                }

                override fun onServiceDisconnected(profile: Int) {
                    callback(emptyList())
                }
            }, BluetoothProfile.A2DP)
        }.onFailure { callback(emptyList()) }
    }

    @Suppress("MissingPermission")
    private fun BluetoothDevice.isAudioCapable(): Boolean {
        val majorClass = bluetoothClass?.majorDeviceClass ?: return false
        return majorClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ||
            majorClass == android.bluetooth.BluetoothClass.Device.Major.PHONE
    }
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isAudio: Boolean,
    val isConnected: Boolean = false
)
