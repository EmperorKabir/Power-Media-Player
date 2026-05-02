package com.powermediaplayer.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

        // Bluetooth media-button remapping. Stored as the action token
        // strings declared in BluetoothMediaActions to keep the schema
        // free of magic ints.
        val BT_PREV_ACTION = stringPreferencesKey("bt_prev_action")
        val BT_NEXT_ACTION = stringPreferencesKey("bt_next_action")
        val BT_SKIP_BACK_SECONDS = intPreferencesKey("bt_skip_back_seconds")
        val BT_SKIP_FORWARD_SECONDS = intPreferencesKey("bt_skip_forward_seconds")

        // Drive favourite folders. Each entry is "id|displayName" so we
        // preserve the human label without an extra round-trip to the
        // Drive API on every browse.
        val DRIVE_FAVOURITE_FOLDERS = stringSetPreferencesKey("drive_favourite_folders")
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

    // ── Bluetooth Media-Button Mapping ───────────────────────────
    val btPrevAction: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BT_PREV_ACTION] ?: BluetoothMediaActions.PREV_TRACK
    }

    suspend fun setBtPrevAction(action: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BT_PREV_ACTION] = action
        }
    }

    val btNextAction: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BT_NEXT_ACTION] ?: BluetoothMediaActions.NEXT_TRACK
    }

    suspend fun setBtNextAction(action: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BT_NEXT_ACTION] = action
        }
    }

    val btSkipBackSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BT_SKIP_BACK_SECONDS] ?: 30
    }

    suspend fun setBtSkipBackSeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BT_SKIP_BACK_SECONDS] = seconds.coerceIn(1, 600)
        }
    }

    val btSkipForwardSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BT_SKIP_FORWARD_SECONDS] ?: 30
    }

    suspend fun setBtSkipForwardSeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BT_SKIP_FORWARD_SECONDS] = seconds.coerceIn(1, 600)
        }
    }

    // ── Drive favourite folders ──────────────────────────────────
    val driveFavouriteFolders: Flow<List<DriveFavouriteFolder>> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.DRIVE_FAVOURITE_FOLDERS] ?: emptySet())
                .mapNotNull { entry ->
                    val sep = entry.indexOf('|')
                    if (sep <= 0) return@mapNotNull null
                    DriveFavouriteFolder(
                        id = entry.substring(0, sep),
                        name = entry.substring(sep + 1)
                    )
                }
                .sortedBy { it.name.lowercase() }
        }

    suspend fun toggleDriveFavouriteFolder(id: String, name: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.DRIVE_FAVOURITE_FOLDERS] ?: emptySet()).toMutableSet()
            val existing = current.firstOrNull { it.startsWith("$id|") }
            if (existing != null) current.remove(existing)
            else current.add("$id|$name")
            prefs[Keys.DRIVE_FAVOURITE_FOLDERS] = current
        }
    }

    /**
     * Synchronous snapshot of the Bluetooth mapping — used by
     * PlaybackService.MediaSession.Callback which is invoked on the
     * binder thread and cannot block on a Flow.collect.
     */
    suspend fun btMappingSnapshot(): BtMappingSnapshot {
        val prefs = context.dataStore.data.first()
        return BtMappingSnapshot(
            prevAction = prefs[Keys.BT_PREV_ACTION] ?: BluetoothMediaActions.PREV_TRACK,
            nextAction = prefs[Keys.BT_NEXT_ACTION] ?: BluetoothMediaActions.NEXT_TRACK,
            skipBackSeconds = prefs[Keys.BT_SKIP_BACK_SECONDS] ?: 30,
            skipForwardSeconds = prefs[Keys.BT_SKIP_FORWARD_SECONDS] ?: 30
        )
    }
}

/**
 * String tokens for Bluetooth media-button actions. Stored verbatim
 * in DataStore so a future schema change is grep-able.
 */
object BluetoothMediaActions {
    const val PREV_TRACK = "previous_track"
    const val NEXT_TRACK = "next_track"
    const val SKIP_BACK = "skip_back_seconds"
    const val SKIP_FORWARD = "skip_forward_seconds"
    const val RESTART_TRACK = "restart_track"
    const val PREV_CHAPTER = "previous_chapter"
    const val NEXT_CHAPTER = "next_chapter"
}

/**
 * Persisted reference to a Drive folder the user has starred.
 */
data class DriveFavouriteFolder(val id: String, val name: String)

/**
 * Immutable snapshot for the binder-thread MediaSession.Callback.
 */
data class BtMappingSnapshot(
    val prevAction: String,
    val nextAction: String,
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int
)
