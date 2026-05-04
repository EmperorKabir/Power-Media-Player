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
        // Drive favourite tracks (saved track IDs + names).
        val DRIVE_FAVOURITE_TRACKS = stringSetPreferencesKey("drive_favourite_tracks")
        // Spotify favourites — separate buckets for tracks/albums/podcasts.
        // Each entry: "spotify:track:ID|name", "spotify:album:ID|name",
        // "spotify:show:ID|name".
        val SPOTIFY_FAVOURITE_TRACKS = stringSetPreferencesKey("spotify_favourite_tracks")
        val SPOTIFY_FAVOURITE_ALBUMS = stringSetPreferencesKey("spotify_favourite_albums")
        val SPOTIFY_FAVOURITE_PODCASTS = stringSetPreferencesKey("spotify_favourite_podcasts")

        // Power-user video effects (M).
        val VIDEO_FLIP_HORIZONTAL = booleanPreferencesKey("video_flip_h")
        val VIDEO_FLIP_VERTICAL = booleanPreferencesKey("video_flip_v")
        val VIDEO_BLACK_AND_WHITE = booleanPreferencesKey("video_bw")
        val VIDEO_SEPIA = booleanPreferencesKey("video_sepia")
        val VIDEO_INVERT = booleanPreferencesKey("video_invert")
        val VIDEO_ROTATION = intPreferencesKey("video_rotation")  // 0/90/180/270

        // Power-user audio (M).
        val AUDIO_REVERSE_LOCAL = booleanPreferencesKey("audio_reverse_local")
        // Audio effects panel — preset reverb, stereo flip, mono mix.
        val REVERB_PRESET = intPreferencesKey("reverb_preset")          // 0=off,1=Room,2=MediumHall,3=LargeHall,4=Plate,5=Cave
        val STEREO_FLIP = booleanPreferencesKey("stereo_flip")
        val MONO_MIX = booleanPreferencesKey("mono_mix")
        // Multi-channel passthrough — when on, ExoPlayer hands the
        // bitstream to the OS unchanged so a connected receiver/HDMI
        // sink decodes 5.1/7.1/Atmos itself.
        val PASSTHROUGH_AUDIO = booleanPreferencesKey("passthrough_audio")
        val PITCH_INDEPENDENT = floatPreferencesKey("pitch_independent")
        val VOLUME_BOOST_MB = intPreferencesKey("volume_boost_mb")
        val SUBTITLE_DELAY_MS = intPreferencesKey("subtitle_delay_ms")
        val AUDIO_DELAY_MS = intPreferencesKey("audio_delay_ms")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        val REPLAY_GAIN_ENABLED = booleanPreferencesKey("replay_gain_enabled")
        val CROSSFADE_MS = intPreferencesKey("crossfade_ms")
        val RESUME_ON_BT = booleanPreferencesKey("resume_on_bt")
        val PREFETCH_NEXT_CLOUD = booleanPreferencesKey("prefetch_next_cloud")

        val LIBRARY_SORT_MODE = stringPreferencesKey("library_sort_mode")

        // Storage Access Framework: tree URIs the user has granted via
        // ACTION_OPEN_DOCUMENT_TREE. Each entry is "treeUri|displayName".
        val DRIVE_PICKED_ROOTS = stringSetPreferencesKey("drive_picked_roots")

        // Drive OAuth (drive.file scope): folder ids the user has
        // granted via the Drive Picker WebView. Each entry "id|name".
        val DRIVE_OAUTH_PICKED_FOLDERS = stringSetPreferencesKey("drive_oauth_picked_folders")
    }

    // ── Drive OAuth picked folders (drive.file via JS Picker) ─────
    val driveOauthPickedFolders: Flow<List<DriveFavouriteFolder>> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.DRIVE_OAUTH_PICKED_FOLDERS] ?: emptySet()).mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0) null
                else DriveFavouriteFolder(
                    id = entry.substring(0, sep),
                    name = entry.substring(sep + 1)
                )
            }.sortedBy { it.name.lowercase() }
        }

    suspend fun addDriveOauthPickedFolder(id: String, name: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.DRIVE_OAUTH_PICKED_FOLDERS] ?: emptySet()).toMutableSet()
            current.removeAll { it.startsWith("$id|") }
            current.add("$id|$name")
            prefs[Keys.DRIVE_OAUTH_PICKED_FOLDERS] = current
        }
    }

    suspend fun removeDriveOauthPickedFolder(id: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.DRIVE_OAUTH_PICKED_FOLDERS] ?: emptySet()).toMutableSet()
            current.removeAll { it.startsWith("$id|") }
            prefs[Keys.DRIVE_OAUTH_PICKED_FOLDERS] = current
        }
    }

    suspend fun clearDriveOauthPickedFolders() {
        context.dataStore.edit { prefs ->
            prefs[Keys.DRIVE_OAUTH_PICKED_FOLDERS] = emptySet()
        }
    }

    // ── Drive (SAF) picked roots ────────────────────────────────
    val drivePickedRoots: Flow<List<DrivePickedRoot>> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.DRIVE_PICKED_ROOTS] ?: emptySet()).mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0) null
                else DrivePickedRoot(
                    treeUri = entry.substring(0, sep),
                    name = entry.substring(sep + 1)
                )
            }.sortedBy { it.name.lowercase() }
        }

    suspend fun addDrivePickedRoot(treeUri: String, name: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.DRIVE_PICKED_ROOTS] ?: emptySet()).toMutableSet()
            // De-dupe by treeUri prefix so re-picking the same folder is a no-op.
            current.removeAll { it.startsWith("$treeUri|") }
            current.add("$treeUri|$name")
            prefs[Keys.DRIVE_PICKED_ROOTS] = current
        }
    }

    suspend fun removeDrivePickedRoot(treeUri: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.DRIVE_PICKED_ROOTS] ?: emptySet()).toMutableSet()
            current.removeAll { it.startsWith("$treeUri|") }
            prefs[Keys.DRIVE_PICKED_ROOTS] = current
        }
    }

    suspend fun clearDrivePickedRoots() {
        context.dataStore.edit { prefs ->
            prefs[Keys.DRIVE_PICKED_ROOTS] = emptySet()
        }
    }

    val librarySortMode: Flow<String> = context.dataStore.data.map {
        it[Keys.LIBRARY_SORT_MODE] ?: "NAME_ASC"
    }
    suspend fun setLibrarySortMode(mode: String) {
        context.dataStore.edit { it[Keys.LIBRARY_SORT_MODE] = mode }
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

    val driveFavouriteTracks: Flow<List<DriveFavouriteFolder>> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.DRIVE_FAVOURITE_TRACKS] ?: emptySet()).mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0) null
                else DriveFavouriteFolder(entry.substring(0, sep), entry.substring(sep + 1))
            }.sortedBy { it.name.lowercase() }
        }

    suspend fun toggleDriveFavouriteTrack(id: String, name: String) {
        toggleSetEntry(Keys.DRIVE_FAVOURITE_TRACKS, id, name)
    }

    val spotifyFavouriteTracks: Flow<List<SpotifyFavourite>> =
        spotifyFavSet(Keys.SPOTIFY_FAVOURITE_TRACKS)
    val spotifyFavouriteAlbums: Flow<List<SpotifyFavourite>> =
        spotifyFavSet(Keys.SPOTIFY_FAVOURITE_ALBUMS)
    val spotifyFavouritePodcasts: Flow<List<SpotifyFavourite>> =
        spotifyFavSet(Keys.SPOTIFY_FAVOURITE_PODCASTS)

    suspend fun toggleSpotifyFavouriteTrack(uri: String, name: String) =
        toggleSetEntry(Keys.SPOTIFY_FAVOURITE_TRACKS, uri, name)
    suspend fun toggleSpotifyFavouriteAlbum(uri: String, name: String) =
        toggleSetEntry(Keys.SPOTIFY_FAVOURITE_ALBUMS, uri, name)
    suspend fun toggleSpotifyFavouritePodcast(uri: String, name: String) =
        toggleSetEntry(Keys.SPOTIFY_FAVOURITE_PODCASTS, uri, name)

    private fun spotifyFavSet(key: Preferences.Key<Set<String>>): Flow<List<SpotifyFavourite>> =
        context.dataStore.data.map { prefs ->
            (prefs[key] ?: emptySet()).mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0) null
                else SpotifyFavourite(entry.substring(0, sep), entry.substring(sep + 1))
            }.sortedBy { it.name.lowercase() }
        }

    private suspend fun toggleSetEntry(
        key: Preferences.Key<Set<String>>,
        id: String,
        name: String
    ) {
        context.dataStore.edit { prefs ->
            val current = (prefs[key] ?: emptySet()).toMutableSet()
            val existing = current.firstOrNull { it.startsWith("$id|") }
            if (existing != null) current.remove(existing)
            else current.add("$id|$name")
            prefs[key] = current
        }
    }

    // ── Power-user toggles ──────────────────────────────────────
    val videoFlipH: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIDEO_FLIP_HORIZONTAL] ?: false }
    val videoFlipV: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIDEO_FLIP_VERTICAL] ?: false }
    val videoBw: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIDEO_BLACK_AND_WHITE] ?: false }
    val videoSepia: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIDEO_SEPIA] ?: false }
    val videoInvert: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIDEO_INVERT] ?: false }
    val videoRotation: Flow<Int> = context.dataStore.data.map { it[Keys.VIDEO_ROTATION] ?: 0 }
    val audioReverseLocal: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUDIO_REVERSE_LOCAL] ?: false }
    val reverbPreset: Flow<Int> = context.dataStore.data.map { it[Keys.REVERB_PRESET] ?: 0 }
    val stereoFlip: Flow<Boolean> = context.dataStore.data.map { it[Keys.STEREO_FLIP] ?: false }
    val monoMix: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONO_MIX] ?: false }
    val passthroughAudio: Flow<Boolean> = context.dataStore.data.map { it[Keys.PASSTHROUGH_AUDIO] ?: true }
    suspend fun setReverbPreset(preset: Int) { context.dataStore.edit { it[Keys.REVERB_PRESET] = preset.coerceIn(0, 5) } }
    suspend fun setStereoFlip(v: Boolean) { context.dataStore.edit { it[Keys.STEREO_FLIP] = v } }
    suspend fun setMonoMix(v: Boolean) { context.dataStore.edit { it[Keys.MONO_MIX] = v } }
    suspend fun setPassthroughAudio(v: Boolean) { context.dataStore.edit { it[Keys.PASSTHROUGH_AUDIO] = v } }
    val pitchIndependent: Flow<Float> = context.dataStore.data.map { it[Keys.PITCH_INDEPENDENT] ?: 1.0f }
    val volumeBoostMb: Flow<Int> = context.dataStore.data.map { it[Keys.VOLUME_BOOST_MB] ?: 0 }
    val subtitleDelayMs: Flow<Int> = context.dataStore.data.map { it[Keys.SUBTITLE_DELAY_MS] ?: 0 }
    val audioDelayMs: Flow<Int> = context.dataStore.data.map { it[Keys.AUDIO_DELAY_MS] ?: 0 }
    val gaplessPlayback: Flow<Boolean> = context.dataStore.data.map { it[Keys.GAPLESS_PLAYBACK] ?: true }
    val replayGainEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.REPLAY_GAIN_ENABLED] ?: false }
    val crossfadeMs: Flow<Int> = context.dataStore.data.map { it[Keys.CROSSFADE_MS] ?: 0 }
    val resumeOnBt: Flow<Boolean> = context.dataStore.data.map { it[Keys.RESUME_ON_BT] ?: false }
    val prefetchNextCloud: Flow<Boolean> = context.dataStore.data.map { it[Keys.PREFETCH_NEXT_CLOUD] ?: true }

    suspend fun setVideoFlipH(v: Boolean) { context.dataStore.edit { it[Keys.VIDEO_FLIP_HORIZONTAL] = v } }
    suspend fun setVideoFlipV(v: Boolean) { context.dataStore.edit { it[Keys.VIDEO_FLIP_VERTICAL] = v } }
    suspend fun setVideoBw(v: Boolean) { context.dataStore.edit { it[Keys.VIDEO_BLACK_AND_WHITE] = v } }
    suspend fun setVideoSepia(v: Boolean) { context.dataStore.edit { it[Keys.VIDEO_SEPIA] = v } }
    suspend fun setVideoInvert(v: Boolean) { context.dataStore.edit { it[Keys.VIDEO_INVERT] = v } }
    suspend fun setVideoRotation(deg: Int) { context.dataStore.edit { it[Keys.VIDEO_ROTATION] = deg } }
    suspend fun setAudioReverseLocal(v: Boolean) { context.dataStore.edit { it[Keys.AUDIO_REVERSE_LOCAL] = v } }
    suspend fun setPitchIndependent(p: Float) { context.dataStore.edit { it[Keys.PITCH_INDEPENDENT] = p } }
    suspend fun setVolumeBoostMb(mb: Int) { context.dataStore.edit { it[Keys.VOLUME_BOOST_MB] = mb } }
    suspend fun setSubtitleDelayMs(ms: Int) { context.dataStore.edit { it[Keys.SUBTITLE_DELAY_MS] = ms } }
    suspend fun setAudioDelayMs(ms: Int) { context.dataStore.edit { it[Keys.AUDIO_DELAY_MS] = ms } }
    suspend fun setGaplessPlayback(v: Boolean) { context.dataStore.edit { it[Keys.GAPLESS_PLAYBACK] = v } }
    suspend fun setReplayGainEnabled(v: Boolean) { context.dataStore.edit { it[Keys.REPLAY_GAIN_ENABLED] = v } }
    suspend fun setCrossfadeMs(ms: Int) { context.dataStore.edit { it[Keys.CROSSFADE_MS] = ms } }
    suspend fun setResumeOnBt(v: Boolean) { context.dataStore.edit { it[Keys.RESUME_ON_BT] = v } }
    suspend fun setPrefetchNextCloud(v: Boolean) { context.dataStore.edit { it[Keys.PREFETCH_NEXT_CLOUD] = v } }

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
 * Persisted reference to a Storage Access Framework tree URI the user
 * has picked via the system folder picker. `treeUri` is the
 * `content://` URI returned by ACTION_OPEN_DOCUMENT_TREE; `name` is
 * the human-readable name shown in the cloud browser root.
 */
data class DrivePickedRoot(val treeUri: String, val name: String)

/**
 * Persisted reference to a Spotify URI the user has starred.
 * id is the spotify:track:..., spotify:album:..., or spotify:show:...
 */
data class SpotifyFavourite(val id: String, val name: String)

/**
 * Immutable snapshot for the binder-thread MediaSession.Callback.
 */
data class BtMappingSnapshot(
    val prevAction: String,
    val nextAction: String,
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int
)
