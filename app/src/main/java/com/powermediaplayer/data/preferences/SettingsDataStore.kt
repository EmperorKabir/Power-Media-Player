package com.powermediaplayer.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * DataStore-backed settings manager for all app preferences.
 * Provides Flow-based reactive reads and suspend writes.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    private val context: Context
) {
    // ── Keys ─────────────────────────────────────────────────────
    private object Keys {
        val METADATA_DEEP_SCAN = booleanPreferencesKey("metadata_deep_scan")
        val SUBTITLE_FORMAT = stringPreferencesKey("subtitle_format")
        val USE_SOFTWARE_DECODING = booleanPreferencesKey("use_software_decoding")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SLEEP_TIMER_MINUTES = intPreferencesKey("sleep_timer_minutes")
        val LAST_EQ_PRESET_ID = longPreferencesKey("last_eq_preset_id")
        val BRIGHTNESS_OVERRIDE = floatPreferencesKey("brightness_override")
    }

    // ── Metadata Extraction Mode ─────────────────────────────────
    val useDeepScan: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.METADATA_DEEP_SCAN] ?: false
    }

    suspend fun setDeepScan(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.METADATA_DEEP_SCAN] = enabled
        }
    }

    // ── Subtitle Format ──────────────────────────────────────────
    val subtitleFormat: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SUBTITLE_FORMAT] ?: "SRT"
    }

    suspend fun setSubtitleFormat(format: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SUBTITLE_FORMAT] = format
        }
    }

    // ── Software Decoding ────────────────────────────────────────
    val useSoftwareDecoding: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.USE_SOFTWARE_DECODING] ?: false
    }

    suspend fun setSoftwareDecoding(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.USE_SOFTWARE_DECODING] = enabled
        }
    }

    // ── Playback Speed ───────────────────────────────────────────
    val playbackSpeed: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.PLAYBACK_SPEED] ?: 1.0f
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PLAYBACK_SPEED] = speed
        }
    }

    // ── Sleep Timer ──────────────────────────────────────────────
    val sleepTimerMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.SLEEP_TIMER_MINUTES] ?: 0
    }

    suspend fun setSleepTimerMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SLEEP_TIMER_MINUTES] = minutes
        }
    }

    // ── Last EQ Preset ───────────────────────────────────────────
    val lastEqPresetId: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_EQ_PRESET_ID] ?: -1L
    }

    suspend fun setLastEqPresetId(id: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_EQ_PRESET_ID] = id
        }
    }

    // ── Brightness Override ──────────────────────────────────────
    val brightnessOverride: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.BRIGHTNESS_OVERRIDE] ?: -1f
    }

    suspend fun setBrightnessOverride(brightness: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BRIGHTNESS_OVERRIDE] = brightness
        }
    }
}
