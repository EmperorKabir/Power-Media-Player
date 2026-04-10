package com.powermediaplayer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager

/**
 * Helper for managing screen brightness with WRITE_SETTINGS permission.
 * Falls back to window-level brightness when system permission is denied.
 */
object BrightnessHelper {

    /**
     * Check if the app has WRITE_SETTINGS permission.
     */
    fun canWriteSettings(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    /**
     * Open the system settings page for granting WRITE_SETTINGS permission.
     */
    fun requestWriteSettingsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Get current system brightness (0-255).
     */
    fun getSystemBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (e: Settings.SettingNotFoundException) {
            128
        }
    }

    /**
     * Set system brightness (0-255). Requires WRITE_SETTINGS permission.
     */
    fun setSystemBrightness(context: Context, brightness: Int) {
        if (!canWriteSettings(context)) return

        // Disable auto-brightness first
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )

        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            brightness.coerceIn(1, 255)
        )
    }

    /**
     * Get brightness as a float (0.0 - 1.0).
     */
    fun getBrightnessFloat(context: Context): Float {
        return getSystemBrightness(context) / 255f
    }

    /**
     * Set brightness from a float (0.0 - 1.0).
     */
    fun setBrightnessFloat(context: Context, brightness: Float) {
        setSystemBrightness(context, (brightness * 255).toInt())
    }
}
