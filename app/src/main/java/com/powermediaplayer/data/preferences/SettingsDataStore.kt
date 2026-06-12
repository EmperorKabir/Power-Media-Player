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
        val HEADPHONE_EQ_PRESET_ID = longPreferencesKey("headphone_eq_preset_id")
        val THEME_ACCENT_HEX = stringPreferencesKey("theme_accent_hex")
        val FONT_SIZE_SCALE = floatPreferencesKey("font_size_scale")
        val DIAG_LOG_ENABLED = booleanPreferencesKey("diag_log_enabled")
        val BT_VIDEO_AUDIO_OFFSET_MS = intPreferencesKey("bt_video_audio_offset_ms")
        val REVERB_WET_MIX = floatPreferencesKey("reverb_wet_mix")
        // §C13 — per-paired-device EQ preset map. Set of "addr|presetId".
        // Address-keyed because device names aren't stable; the BT MAC
        // is. presetId = -1 means "Don't auto-switch for this device".
        val HEADPHONE_EQ_DEVICE_PRESETS = stringSetPreferencesKey("headphone_eq_device_presets")
        // §C28 — Drive offline copy registry. Set of "id|absolutePath".
        val OFFLINE_DRIVE_PAIRS = stringSetPreferencesKey("offline_drive_pairs")
        // §C28 LOCKED — offline storage cap in bytes; 0 = unlimited.
        // Defaults to 5 GB per the locked spec.
        val OFFLINE_STORAGE_LIMIT_BYTES = androidx.datastore.preferences.core.longPreferencesKey(
            "offline_storage_limit_bytes"
        )
        // §C9 — OpenSubtitles credentials.
        val OPENSUBS_TOKEN = stringPreferencesKey("opensubs_token")
        val OPENSUBS_EMAIL = stringPreferencesKey("opensubs_email")
        val OPENSUBS_API_KEY = stringPreferencesKey("opensubs_api_key")
        // §C9 A2.2-A2.5 — search & save preferences.
        val OPENSUBS_LANGUAGES = stringSetPreferencesKey("opensubs_languages")
        val OPENSUBS_MATCH_BY_HASH = booleanPreferencesKey("opensubs_match_by_hash")
        val OPENSUBS_SAVE_NEXT_TO_VIDEO = booleanPreferencesKey("opensubs_save_next_to_video")
        val OPENSUBS_OVERRIDE_EXISTING = booleanPreferencesKey("opensubs_override_existing")
        // §C18 LOCKED — Track gain vs Album gain mode.
        val REPLAY_GAIN_MODE = stringPreferencesKey("replay_gain_mode")
        // §C17 LOCKED — sub-toggles + apply scope.
        val ENRICH_FETCH_ARTWORK = booleanPreferencesKey("enrich_fetch_artwork")
        val ENRICH_FETCH_YEAR = booleanPreferencesKey("enrich_fetch_year")
        val ENRICH_FETCH_GENRE = booleanPreferencesKey("enrich_fetch_genre")
        val ENRICH_APPLY_SCOPE = stringPreferencesKey("enrich_apply_scope")
        // §C25 / A9 — per-file tag overrides ("Edit tags" menu item).
        // Set of "urititleartistalbum" entries.
        val TAG_OVERRIDES = stringSetPreferencesKey("tag_overrides")
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

        // Auto-hide control timers — seconds. 0 means "Never auto-hide".
        // Defaults: video controls 4s (the only one user wanted to keep
        // ticking by default); every popup defaults to Never per user
        // directive — they pop AT EXPLICIT TAP and shouldn't time out.
        val VIDEO_CONTROLS_HIDE_SEC = intPreferencesKey("video_controls_hide_sec")
        val AUDIO_EFFECTS_POPUP_HIDE_SEC = intPreferencesKey("audio_effects_popup_hide_sec")
        val VIDEO_EFFECTS_POPUP_HIDE_SEC = intPreferencesKey("video_effects_popup_hide_sec")
        val CROSSFADE_POPUP_HIDE_SEC = intPreferencesKey("crossfade_popup_hide_sec")
        val INFO_SHEET_HIDE_SEC = intPreferencesKey("info_sheet_hide_sec")
        val TRACK_CONTEXT_SHEET_HIDE_SEC = intPreferencesKey("track_context_sheet_hide_sec")
        val AUDIO_CONTROLS_HIDE_FOLDED_SEC = intPreferencesKey("audio_controls_hide_folded_sec")
        val AUDIO_CONTROLS_HIDE_TABLET_SEC = intPreferencesKey("audio_controls_hide_tablet_sec")

        // Hidden files (§C27) — set of URIs (toString()) the user has
        // chosen to hide from Library / Cloud lists. Files stay on the
        // device; only the in-app filter is affected. Survives reinstall
        // via Auto Backup (manifest already has allowBackup="true").
        val HIDDEN_URIS = stringSetPreferencesKey("hidden_uris")

        // Crossfade panel (Phase 4 / §B2). 9 sub-toggles + 1 master.
        // Existing CROSSFADE_MS retained as the duration field; the
        // master switch is a separate boolean so users can toggle off
        // without losing their preferred duration. Defaults locked
        // from §B2: master OFF, 5 s, equal-power, album-mode ON, all
        // others OFF.
        val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        val CROSSFADE_CURVE = stringPreferencesKey("crossfade_curve")
        val CROSSFADE_ALBUM_MODE = booleanPreferencesKey("crossfade_album_mode")
        val CROSSFADE_SKIP_SILENCE = booleanPreferencesKey("crossfade_skip_silence")
        val CROSSFADE_PRE_FADE_TRIGGER_S = intPreferencesKey("crossfade_pre_fade_trigger_s")
        val CROSSFADE_MANUAL_FADE_NOW_ENABLED = booleanPreferencesKey("crossfade_manual_fade_now_enabled")
        val CROSSFADE_FADE_OUT_ON_PAUSE = booleanPreferencesKey("crossfade_fade_out_on_pause")
        val CROSSFADE_FADE_IN_ON_RESUME = booleanPreferencesKey("crossfade_fade_in_on_resume")

        // §C22 — auto-play when headphones plug in. Default OFF; opt-in
        // because surprise audio is annoying.
        val HEADPHONE_PLUG_AUTOPLAY = booleanPreferencesKey("headphone_plug_autoplay")
        val RESTORE_LAST_ON_LAUNCH = booleanPreferencesKey("restore_last_on_launch")
        val AUTOPLAY_ON_LAUNCH = booleanPreferencesKey("autoplay_on_launch")

        // §C11 — fade volume over the last 30 s of a sleep timer instead
        // of an abrupt pause. Off by default so existing behaviour is
        // unchanged for users who haven't opted in.
        val SLEEP_TIMER_FADE_OUT = booleanPreferencesKey("sleep_timer_fade_out")

        // §C3 — Tasker / external-intent control. Master switch. When
        // OFF, the BroadcastReceiver short-circuits and ignores every
        // action — protects against unwanted external automation.
        // Default OFF; user must explicitly opt in.
        val TASKER_INTENTS_ENABLED = booleanPreferencesKey("tasker_intents_enabled")

        // §C7 (slim) — per-file playback speed overrides. Each entry
        // is "mediaUri|speedFloat" e.g. "file:///foo.mp3|1.50". Auto
        // applied on track load; updated whenever the user adjusts
        // speed on a track. Persists across sessions. Phase 5's full
        // per-file override system (audio + video effects + speed +
        // pitch via Room) will replace this with a richer model.
        val SPEED_OVERRIDES = stringSetPreferencesKey("speed_overrides")

        // §C7 (slim) — per-file A-B loop markers. Each entry is
        // "mediaUri|startMs|endMs". Auto-restored on track load so
        // audiobook learners can repeat a section across opens.
        val AB_LOOP_OVERRIDES = stringSetPreferencesKey("ab_loop_overrides")

        // Bookmark replay context — when tapping a bookmark chip, seek
        // to (bookmark.positionMs - X*1000) so the user lands a bit
        // BEFORE the saved moment for context. Default 10 s. Range 0
        // (exact) to 30 s. Useful for podcast / audiobook listeners
        // marking "huh, what did they say?" moments.
        val BOOKMARK_REPLAY_CONTEXT_SEC = intPreferencesKey("bookmark_replay_context_sec")

        // When the user swipes the app from Recents, should the
        // PlaybackService stop playback (instead of continuing in the
        // foreground)? Default false — most music apps keep playing.
        // Some users prefer the swipe-to-stop semantic.
        val STOP_ON_TASK_REMOVED = booleanPreferencesKey("stop_on_task_removed")

        // Cold-start resume backoff — seek to (lastPositionMs - X*1000)
        // when restoring on cold launch. Default 5 s; podcast / audiobook
        // listeners benefit from a small lead-in to re-anchor where they
        // were. Range 0..30 s.
        val COLD_START_RESUME_BACKOFF_SEC = intPreferencesKey("cold_start_resume_backoff_sec")

        // Low-latency audio buffer. When ON, the AudioTrackBufferSizeProvider
        // returns the platform-minimum buffer size, which makes pitch / speed
        // / effect changes audible sooner (~ 50-100 ms saved) at the cost of
        // higher dropout risk under CPU load. Default OFF — keeps Media3's
        // safety-multiplied default buffer.
        val AUDIO_BUFFER_LOW_LATENCY = booleanPreferencesKey("audio_buffer_low_latency")

        // Webhooks (v1 — single endpoint, per-event toggles). User
        // supplies any HTTPS URL (typical: IFTTT / n8n / Home Assistant
        // / Pushover / Google Apps Script); we fire-and-forget a JSON
        // POST when the matching event happens during playback. Empty
        // URL → no calls fired regardless of event toggles. All events
        // default off — opt-in only.
        val WEBHOOK_URL = stringPreferencesKey("webhook_url")
        val WEBHOOK_ON_PLAY = booleanPreferencesKey("webhook_on_play")
        val WEBHOOK_ON_PAUSE = booleanPreferencesKey("webhook_on_pause")
        val WEBHOOK_ON_RESUME = booleanPreferencesKey("webhook_on_resume")
        val WEBHOOK_ON_SKIP_NEXT = booleanPreferencesKey("webhook_on_skip_next")
        val WEBHOOK_ON_SKIP_PREV = booleanPreferencesKey("webhook_on_skip_prev")
        val WEBHOOK_ON_END = booleanPreferencesKey("webhook_on_end")

        // Hue v1 — LAN-only pair / scene control. Bridge IP + app key
        // are persisted from the pair flow. v2 will add audio-reactive
        // streaming over the Entertainment API; the scene-preset path
        // is what ships now.
        val HUE_BRIDGE_IP = stringPreferencesKey("hue_bridge_ip")
        val HUE_APP_KEY = stringPreferencesKey("hue_app_key")
        // 32-byte hex PSK returned alongside the app key during pair
        // (via the generateclientkey flag). Required by the
        // Entertainment-API DTLS-PSK handshake. Plain DataStore — the
        // key only controls lights on the local network; we already
        // accept untrusted bridge certs anyway.
        val HUE_CLIENT_KEY = stringPreferencesKey("hue_client_key")
        // Entertainment area UUID to stream to. Captured on first
        // stream-start (we fetch /clip/v2/resource/entertainment_
        // configuration and pick the first area). User-visible UI
        // could let them pick later; v1 = first area found.
        val HUE_ENTERTAINMENT_ID = stringPreferencesKey("hue_entertainment_id")
        val HUE_REACTIVE_MODE = stringPreferencesKey("hue_reactive_mode")
        // Intensity 0..100. 0 = audio-reactive off. Replaces the v1
        // mode picker — there is one rich reactive mode whose
        // vividness is the slider value.
        val HUE_REACTIVE_INTENSITY = intPreferencesKey("hue_reactive_intensity")
        // Compensates for the AudioTrack output buffer (~150–250 ms
        // typical) so lights flash AT THE SAME TIME as bass kicks
        // instead of leading them. Default 200 ms — user dials per
        // room.
        val HUE_SYNC_OFFSET_MS = intPreferencesKey("hue_sync_offset_ms")
        // vc29 — user-picked area for reactive lighting. Stored as
        // composite "<kind>:<uuid>" where kind ∈ {room, zone, ent}. The
        // bridge has distinct REST endpoints for rooms vs zones vs
        // entertainment_configurations; this prefix tells the resolver
        // which list to look in. Empty string → fall back to the first
        // entertainment_configuration on the bridge (legacy behaviour).
        val HUE_SELECTED_AREA_KEY = stringPreferencesKey("hue_selected_area")
        // vc29 — SPREAD-vs-COHERENT mode toggle. When ON (default) and
        // the picked area has ≥3 COLOUR lights, each band maps to its
        // own light. When OFF (or fewer than 3 colour lights) all
        // colour lights show the same XY+brightness — feels unified
        // across mixed-tier areas.
        val HUE_SPREAD_BANDS = booleanPreferencesKey("hue_spread_bands")
        // vc29 — drive DIMMABLE (white-only, no colour) lights in
        // parallel to the DTLS Entertainment stream via CLIP REST at
        // ≤10 Hz/light. ONOFF smart plugs are always excluded.
        val HUE_DRIVE_DIMMABLE = booleanPreferencesKey("hue_drive_dimmable")
        // vc29.8 — global user offset added on top of each bulb's
        // auto-detected manufacturer latency. -300..+300 ms.
        val HUE_DIMMABLE_LAG_OFFSET_MS = intPreferencesKey("hue_dimmable_lag_offset_ms")

        // §C14 — audio focus policy. Three independent settings for the
        // common interruption types. Defaults at first install:
        //  - Phone calls → Pause (always; can't safely play during call)
        //  - Notifications → Duck (lower volume, keep playing)
        //  - Other media → Pause (yields to the new audio)
        // Token values: "pause" | "duck" | "ignore".
        val AUDIO_FOCUS_ON_CALL = stringPreferencesKey("audio_focus_on_call")
        val AUDIO_FOCUS_ON_NOTIFICATION = stringPreferencesKey("audio_focus_on_notification")
        val AUDIO_FOCUS_ON_OTHER_MEDIA = stringPreferencesKey("audio_focus_on_other_media")

        // §C17 — online metadata enrichment via Discogs + MusicBrainz.
        // Stub: just the toggle for now; the actual API integrations
        // are heavy and ship in a follow-up. Default OFF.
        val METADATA_ENRICHMENT_ENABLED = booleanPreferencesKey("metadata_enrichment_enabled")
        val METADATA_ENRICHMENT_PROVIDER = stringPreferencesKey("metadata_enrichment_provider")

        // §C18 — auto-scan ReplayGain on import. When ON, every new
        // MediaStore-discovered audio file gets a loudness scan + the
        // result written to the bookmarks Room table as a sidecar.
        // Default OFF. Scan logic stubbed pending BS.1770 implementation.
        val REPLAYGAIN_AUTO_SCAN = booleanPreferencesKey("replaygain_auto_scan")

        // §F — first-run deep-scan opt-in seen flag. Set true after the
        // user has answered the one-time prompt (either accept or
        // skip). Never reset.
        val FIRST_RUN_SEEN = booleanPreferencesKey("first_run_seen")

        // §C12 — scheduled alarms. Each entry is the AlarmRecord
        // serialized form 'id|hour|minute|days|mediaUri|enabled'.
        val SCHEDULED_ALARMS = stringSetPreferencesKey("scheduled_alarms")

        // Cover-art scaling mode for the now-playing surface — "fit"
        // (default; show whole cover with margins) or "fill" (no
        // margins, may crop edges).
        val ARTWORK_SCALE_MODE = stringPreferencesKey("artwork_scale_mode")

        // Storage Access Framework: tree URIs the user has granted via
        // ACTION_OPEN_DOCUMENT_TREE. Each entry is "treeUri|displayName".
        val DRIVE_PICKED_ROOTS = stringSetPreferencesKey("drive_picked_roots")

        // Drive OAuth (drive.file scope): folder ids the user has
        // granted via the Drive Picker WebView. Each entry "id|name".
        val DRIVE_OAUTH_PICKED_FOLDERS = stringSetPreferencesKey("drive_oauth_picked_folders")

        // One-shot acknowledgement that the user has read the
        // "pick a FOLDER, not a file" Drive Picker warning. Set true
        // the first time the user taps the "I understand" button on
        // the warning dialog; never reset.
        val DRIVE_FIRST_PICK_WARNING_SEEN = booleanPreferencesKey("drive_first_pick_warning_seen")
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

    val driveFirstPickWarningSeen: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.DRIVE_FIRST_PICK_WARNING_SEEN] ?: false
    }
    suspend fun markDriveFirstPickWarningSeen() {
        context.dataStore.edit { it[Keys.DRIVE_FIRST_PICK_WARNING_SEEN] = true }
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

    // ── Auto-hide control timers (Phase 2) ────────────────────────────
    // Seconds. 0 = Never auto-hide (controls stay visible). Defaults
    // preserve current behaviour: video controls hide after 4 s; popups
    // after 3 s. Audio-mode auto-hide deferred to a later phase.
    val videoControlsHideSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.VIDEO_CONTROLS_HIDE_SEC] ?: 4
    }
    suspend fun setVideoControlsHideSec(seconds: Int) {
        context.dataStore.edit { it[Keys.VIDEO_CONTROLS_HIDE_SEC] = seconds }
    }
    val audioEffectsPopupHideSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUDIO_EFFECTS_POPUP_HIDE_SEC] ?: 0
    }
    suspend fun setAudioEffectsPopupHideSec(seconds: Int) {
        context.dataStore.edit { it[Keys.AUDIO_EFFECTS_POPUP_HIDE_SEC] = seconds }
    }
    val videoEffectsPopupHideSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.VIDEO_EFFECTS_POPUP_HIDE_SEC] ?: 0
    }
    suspend fun setVideoEffectsPopupHideSec(seconds: Int) {
        context.dataStore.edit { it[Keys.VIDEO_EFFECTS_POPUP_HIDE_SEC] = seconds }
    }
    val crossfadePopupHideSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.CROSSFADE_POPUP_HIDE_SEC] ?: 0
    }
    suspend fun setCrossfadePopupHideSec(seconds: Int) {
        context.dataStore.edit { it[Keys.CROSSFADE_POPUP_HIDE_SEC] = seconds }
    }
    val infoSheetHideSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.INFO_SHEET_HIDE_SEC] ?: 0
    }
    suspend fun setInfoSheetHideSec(seconds: Int) {
        context.dataStore.edit { it[Keys.INFO_SHEET_HIDE_SEC] = seconds }
    }
    val trackContextSheetHideSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.TRACK_CONTEXT_SHEET_HIDE_SEC] ?: 0
    }
    suspend fun setTrackContextSheetHideSec(seconds: Int) {
        context.dataStore.edit { it[Keys.TRACK_CONTEXT_SHEET_HIDE_SEC] = seconds }
    }
    val audioControlsHideFoldedSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUDIO_CONTROLS_HIDE_FOLDED_SEC] ?: 0
    }
    suspend fun setAudioControlsHideFoldedSec(seconds: Int) {
        context.dataStore.edit { it[Keys.AUDIO_CONTROLS_HIDE_FOLDED_SEC] = seconds }
    }
    val audioControlsHideTabletSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUDIO_CONTROLS_HIDE_TABLET_SEC] ?: 0
    }
    suspend fun setAudioControlsHideTabletSec(seconds: Int) {
        context.dataStore.edit { it[Keys.AUDIO_CONTROLS_HIDE_TABLET_SEC] = seconds }
    }

    // ── Hidden files (§C27) ───────────────────────────────────────────
    val hiddenUris: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.HIDDEN_URIS] ?: emptySet()
    }
    suspend fun hideUri(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_URIS] ?: emptySet()
            prefs[Keys.HIDDEN_URIS] = current + uri
        }
    }
    suspend fun unhideUri(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_URIS] ?: emptySet()
            prefs[Keys.HIDDEN_URIS] = current - uri
        }
    }
    suspend fun unhideAll() {
        context.dataStore.edit { it[Keys.HIDDEN_URIS] = emptySet() }
    }

    // ── Crossfade panel (Phase 4 / §B2) ───────────────────────────────
    val crossfadeEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CROSSFADE_ENABLED] ?: false
    }
    suspend fun setCrossfadeEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.CROSSFADE_ENABLED] = v }
    }
    val crossfadeCurve: Flow<String> = context.dataStore.data.map {
        it[Keys.CROSSFADE_CURVE] ?: "EQUAL_POWER"
    }
    suspend fun setCrossfadeCurve(v: String) {
        context.dataStore.edit { it[Keys.CROSSFADE_CURVE] = v }
    }
    val crossfadeAlbumMode: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CROSSFADE_ALBUM_MODE] ?: true
    }
    suspend fun setCrossfadeAlbumMode(v: Boolean) {
        context.dataStore.edit { it[Keys.CROSSFADE_ALBUM_MODE] = v }
    }
    val crossfadeSkipSilence: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CROSSFADE_SKIP_SILENCE] ?: false
    }
    suspend fun setCrossfadeSkipSilence(v: Boolean) {
        context.dataStore.edit { it[Keys.CROSSFADE_SKIP_SILENCE] = v }
    }
    val crossfadePreFadeTriggerS: Flow<Int> = context.dataStore.data.map {
        it[Keys.CROSSFADE_PRE_FADE_TRIGGER_S] ?: 5
    }
    suspend fun setCrossfadePreFadeTriggerS(v: Int) {
        context.dataStore.edit { it[Keys.CROSSFADE_PRE_FADE_TRIGGER_S] = v }
    }
    val crossfadeManualFadeNowEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CROSSFADE_MANUAL_FADE_NOW_ENABLED] ?: false
    }
    suspend fun setCrossfadeManualFadeNowEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.CROSSFADE_MANUAL_FADE_NOW_ENABLED] = v }
    }
    val crossfadeFadeOutOnPause: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CROSSFADE_FADE_OUT_ON_PAUSE] ?: false
    }
    suspend fun setCrossfadeFadeOutOnPause(v: Boolean) {
        context.dataStore.edit { it[Keys.CROSSFADE_FADE_OUT_ON_PAUSE] = v }
    }
    val crossfadeFadeInOnResume: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CROSSFADE_FADE_IN_ON_RESUME] ?: false
    }
    suspend fun setCrossfadeFadeInOnResume(v: Boolean) {
        context.dataStore.edit { it[Keys.CROSSFADE_FADE_IN_ON_RESUME] = v }
    }

    // ── §C22 Headphone plug-in resume ─────────────────────────────────
    val headphonePlugAutoplay: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.HEADPHONE_PLUG_AUTOPLAY] ?: false
    }
    suspend fun setHeadphonePlugAutoplay(v: Boolean) {
        context.dataStore.edit { it[Keys.HEADPHONE_PLUG_AUTOPLAY] = v }
    }

    // ── §C11 Sleep timer fade-out ─────────────────────────────────────
    val sleepTimerFadeOut: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.SLEEP_TIMER_FADE_OUT] ?: false
    }
    suspend fun setSleepTimerFadeOut(v: Boolean) {
        context.dataStore.edit { it[Keys.SLEEP_TIMER_FADE_OUT] = v }
    }

    // ── §C3 Tasker / external-intent control ──────────────────────────
    val taskerIntentsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.TASKER_INTENTS_ENABLED] ?: false
    }
    suspend fun setTaskerIntentsEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.TASKER_INTENTS_ENABLED] = v }
    }

    // ── §C7 (slim) — per-file playback speed overrides ────────────────
    val speedOverrides: Flow<Map<String, Float>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.SPEED_OVERRIDES] ?: emptySet()).mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            if (parts.size == 2) {
                val speed = parts[1].toFloatOrNull() ?: return@mapNotNull null
                parts[0] to speed
            } else null
        }.toMap()
    }
    suspend fun setSpeedOverride(uri: String, speed: Float) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SPEED_OVERRIDES] ?: emptySet()
            // Drop any existing entry for this uri before adding the
            // new one so each uri has at most one stored speed.
            val pruned = current.filterNot { it.startsWith("$uri|") }.toSet()
            prefs[Keys.SPEED_OVERRIDES] = pruned + "$uri|${"%.3f".format(speed)}"
        }
    }
    suspend fun clearSpeedOverride(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SPEED_OVERRIDES] ?: emptySet()
            prefs[Keys.SPEED_OVERRIDES] = current.filterNot { it.startsWith("$uri|") }.toSet()
        }
    }

    /** Per-file A-B loop start/end markers. */
    val abLoopOverrides: Flow<Map<String, Pair<Long, Long>>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.AB_LOOP_OVERRIDES] ?: emptySet()).mapNotNull { entry ->
            val parts = entry.split('|', limit = 3)
            if (parts.size == 3) {
                val a = parts[1].toLongOrNull() ?: return@mapNotNull null
                val b = parts[2].toLongOrNull() ?: return@mapNotNull null
                parts[0] to (a to b)
            } else null
        }.toMap()
    }
    suspend fun setAbLoopOverride(uri: String, startMs: Long, endMs: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.AB_LOOP_OVERRIDES] ?: emptySet()
            val pruned = current.filterNot { it.startsWith("$uri|") }.toSet()
            prefs[Keys.AB_LOOP_OVERRIDES] = pruned + "$uri|$startMs|$endMs"
        }
    }
    suspend fun clearAbLoopOverride(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.AB_LOOP_OVERRIDES] ?: emptySet()
            prefs[Keys.AB_LOOP_OVERRIDES] = current.filterNot { it.startsWith("$uri|") }.toSet()
        }
    }

    /** Bookmark replay-context offset in seconds. 0 = exact seek. */
    val bookmarkReplayContextSec: Flow<Int> = context.dataStore.data.map {
        it[Keys.BOOKMARK_REPLAY_CONTEXT_SEC] ?: 10
    }
    suspend fun setBookmarkReplayContextSec(sec: Int) {
        context.dataStore.edit { it[Keys.BOOKMARK_REPLAY_CONTEXT_SEC] = sec.coerceIn(0, 30) }
    }

    val stopOnTaskRemoved: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.STOP_ON_TASK_REMOVED] ?: false
    }
    suspend fun setStopOnTaskRemoved(v: Boolean) {
        context.dataStore.edit { it[Keys.STOP_ON_TASK_REMOVED] = v }
    }

    val coldStartResumeBackoffSec: Flow<Int> = context.dataStore.data.map {
        it[Keys.COLD_START_RESUME_BACKOFF_SEC] ?: 5
    }

    /** Restore the most-recent local/Drive item into a paused player on
     *  app launch. Default ON (matches long-standing behaviour). When
     *  off, the launch restore is skipped entirely; the backoff slider
     *  is only meaningful while this is on. Independent of the
     *  Bluetooth/headphone auto-resume toggles, which act on whatever
     *  is ALREADY loaded in the player. */
    val restoreLastOnLaunch: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.RESTORE_LAST_ON_LAUNCH] ?: true
    }
    suspend fun setRestoreLastOnLaunch(v: Boolean) {
        context.dataStore.edit { it[Keys.RESTORE_LAST_ON_LAUNCH] = v }
    }

    /** With the launch restore on: start PLAYING from the saved spot
     *  instead of waiting paused. Default OFF — no surprise audio. */
    val autoplayOnLaunch: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.AUTOPLAY_ON_LAUNCH] ?: false
    }
    suspend fun setAutoplayOnLaunch(v: Boolean) {
        context.dataStore.edit { it[Keys.AUTOPLAY_ON_LAUNCH] = v }
    }

    val audioBufferLowLatency: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.AUDIO_BUFFER_LOW_LATENCY] ?: false
    }
    suspend fun setAudioBufferLowLatency(v: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_BUFFER_LOW_LATENCY] = v }
    }

    val webhookUrl: Flow<String> = context.dataStore.data.map { it[Keys.WEBHOOK_URL] ?: "" }
    val webhookOnPlay: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEBHOOK_ON_PLAY] ?: false }
    val webhookOnPause: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEBHOOK_ON_PAUSE] ?: false }
    val webhookOnResume: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEBHOOK_ON_RESUME] ?: false }
    val webhookOnSkipNext: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEBHOOK_ON_SKIP_NEXT] ?: false }
    val webhookOnSkipPrev: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEBHOOK_ON_SKIP_PREV] ?: false }
    val webhookOnEnd: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEBHOOK_ON_END] ?: false }
    suspend fun setWebhookUrl(v: String) { context.dataStore.edit { it[Keys.WEBHOOK_URL] = v.trim() } }
    suspend fun setWebhookOnPlay(v: Boolean) { context.dataStore.edit { it[Keys.WEBHOOK_ON_PLAY] = v } }
    suspend fun setWebhookOnPause(v: Boolean) { context.dataStore.edit { it[Keys.WEBHOOK_ON_PAUSE] = v } }
    suspend fun setWebhookOnResume(v: Boolean) { context.dataStore.edit { it[Keys.WEBHOOK_ON_RESUME] = v } }
    suspend fun setWebhookOnSkipNext(v: Boolean) { context.dataStore.edit { it[Keys.WEBHOOK_ON_SKIP_NEXT] = v } }
    suspend fun setWebhookOnSkipPrev(v: Boolean) { context.dataStore.edit { it[Keys.WEBHOOK_ON_SKIP_PREV] = v } }
    suspend fun setWebhookOnEnd(v: Boolean) { context.dataStore.edit { it[Keys.WEBHOOK_ON_END] = v } }

    val hueBridgeIp: Flow<String> = context.dataStore.data.map { it[Keys.HUE_BRIDGE_IP] ?: "" }
    val hueAppKey: Flow<String> = context.dataStore.data.map { it[Keys.HUE_APP_KEY] ?: "" }
    val hueClientKey: Flow<String> = context.dataStore.data.map { it[Keys.HUE_CLIENT_KEY] ?: "" }
    val hueEntertainmentId: Flow<String> = context.dataStore.data.map { it[Keys.HUE_ENTERTAINMENT_ID] ?: "" }
    val hueReactiveMode: Flow<String> = context.dataStore.data.map { it[Keys.HUE_REACTIVE_MODE] ?: "off" }
    suspend fun setHueBridgeIp(v: String) { context.dataStore.edit { it[Keys.HUE_BRIDGE_IP] = v.trim() } }
    suspend fun setHueAppKey(v: String) { context.dataStore.edit { it[Keys.HUE_APP_KEY] = v.trim() } }
    suspend fun setHueClientKey(v: String) { context.dataStore.edit { it[Keys.HUE_CLIENT_KEY] = v.trim() } }
    suspend fun setHueEntertainmentId(v: String) { context.dataStore.edit { it[Keys.HUE_ENTERTAINMENT_ID] = v.trim() } }
    suspend fun setHueReactiveMode(v: String) { context.dataStore.edit { it[Keys.HUE_REACTIVE_MODE] = v } }
    val hueReactiveIntensity: Flow<Int> = context.dataStore.data.map { it[Keys.HUE_REACTIVE_INTENSITY] ?: 0 }
    suspend fun setHueReactiveIntensity(v: Int) { context.dataStore.edit { it[Keys.HUE_REACTIVE_INTENSITY] = v.coerceIn(0, 100) } }
    val hueSyncOffsetMs: Flow<Int> = context.dataStore.data.map { it[Keys.HUE_SYNC_OFFSET_MS] ?: 200 }
    suspend fun setHueSyncOffsetMs(v: Int) { context.dataStore.edit { it[Keys.HUE_SYNC_OFFSET_MS] = v.coerceIn(0, 1000) } }
    // vc29 picker + mode toggles.
    val hueSelectedArea: Flow<String> = context.dataStore.data.map { it[Keys.HUE_SELECTED_AREA_KEY] ?: "" }
    suspend fun setHueSelectedArea(v: String) { context.dataStore.edit { it[Keys.HUE_SELECTED_AREA_KEY] = v.trim() } }
    val hueSpreadBands: Flow<Boolean> = context.dataStore.data.map { it[Keys.HUE_SPREAD_BANDS] ?: true }
    suspend fun setHueSpreadBands(v: Boolean) { context.dataStore.edit { it[Keys.HUE_SPREAD_BANDS] = v } }
    val hueDriveDimmable: Flow<Boolean> = context.dataStore.data.map { it[Keys.HUE_DRIVE_DIMMABLE] ?: true }
    suspend fun setHueDriveDimmable(v: Boolean) { context.dataStore.edit { it[Keys.HUE_DRIVE_DIMMABLE] = v } }
    val hueDimmableLagOffsetMs: Flow<Int> = context.dataStore.data.map {
        (it[Keys.HUE_DIMMABLE_LAG_OFFSET_MS] ?: 0).coerceIn(-300, 300)
    }
    suspend fun setHueDimmableLagOffsetMs(v: Int) {
        context.dataStore.edit { it[Keys.HUE_DIMMABLE_LAG_OFFSET_MS] = v.coerceIn(-300, 300) }
    }
    suspend fun setColdStartResumeBackoffSec(sec: Int) {
        context.dataStore.edit { it[Keys.COLD_START_RESUME_BACKOFF_SEC] = sec.coerceIn(0, 30) }
    }

    // ── §C14 Audio focus policy ───────────────────────────────────────
    val audioFocusOnCall: Flow<String> = context.dataStore.data.map {
        it[Keys.AUDIO_FOCUS_ON_CALL] ?: "pause"
    }
    val audioFocusOnNotification: Flow<String> = context.dataStore.data.map {
        it[Keys.AUDIO_FOCUS_ON_NOTIFICATION] ?: "duck"
    }
    val audioFocusOnOtherMedia: Flow<String> = context.dataStore.data.map {
        it[Keys.AUDIO_FOCUS_ON_OTHER_MEDIA] ?: "pause"
    }
    suspend fun setAudioFocusOnCall(token: String) {
        context.dataStore.edit { it[Keys.AUDIO_FOCUS_ON_CALL] = token }
    }
    suspend fun setAudioFocusOnNotification(token: String) {
        context.dataStore.edit { it[Keys.AUDIO_FOCUS_ON_NOTIFICATION] = token }
    }
    suspend fun setAudioFocusOnOtherMedia(token: String) {
        context.dataStore.edit { it[Keys.AUDIO_FOCUS_ON_OTHER_MEDIA] = token }
    }

    // ── §C17 Online metadata enrichment ───────────────────────────────
    val metadataEnrichmentEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.METADATA_ENRICHMENT_ENABLED] ?: false
    }
    val metadataEnrichmentProvider: Flow<String> = context.dataStore.data.map {
        it[Keys.METADATA_ENRICHMENT_PROVIDER] ?: "musicbrainz"  // free, no key
    }
    suspend fun setMetadataEnrichmentEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.METADATA_ENRICHMENT_ENABLED] = v }
    }
    suspend fun setMetadataEnrichmentProvider(v: String) {
        context.dataStore.edit { it[Keys.METADATA_ENRICHMENT_PROVIDER] = v }
    }

    // ── §C18 ReplayGain auto-scan ─────────────────────────────────────
    val replayGainAutoScan: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.REPLAYGAIN_AUTO_SCAN] ?: false
    }
    suspend fun setReplayGainAutoScan(v: Boolean) {
        context.dataStore.edit { it[Keys.REPLAYGAIN_AUTO_SCAN] = v }
    }

    val firstRunSeen: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.FIRST_RUN_SEEN] ?: false
    }
    suspend fun setFirstRunSeen() {
        context.dataStore.edit { it[Keys.FIRST_RUN_SEEN] = true }
    }

    // §C12 — scheduled alarms.
    val scheduledAlarms: Flow<List<com.powermediaplayer.alarm.AlarmRecord>> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.SCHEDULED_ALARMS] ?: emptySet())
                .mapNotNull { com.powermediaplayer.alarm.AlarmRecord.deserialize(it) }
                .sortedBy { it.hour * 60 + it.minute }
        }
    suspend fun upsertAlarm(alarm: com.powermediaplayer.alarm.AlarmRecord) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SCHEDULED_ALARMS] ?: emptySet()
            val pruned = current.filterNot { it.startsWith("${alarm.id}|") }.toSet()
            prefs[Keys.SCHEDULED_ALARMS] = pruned + alarm.serialize()
        }
    }
    suspend fun deleteAlarm(id: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SCHEDULED_ALARMS] ?: emptySet()
            prefs[Keys.SCHEDULED_ALARMS] = current.filterNot { it.startsWith("$id|") }.toSet()
        }
    }

    /**
     * One-tap "Reset to defaults" — clears every preference so the
     * next read returns the in-code default. Triggered from Settings →
     * About → "Reset all settings". Library favourites + Drive picked
     * folders + Spotify favourites stay untouched (those are stored
     * separately in Room / DataStore set keys that ARE wiped here too,
     * which IS the user's intent for a "fresh start"). User confirmed
     * via the destructive-action AlertDialog before this fires.
     */
    suspend fun resetAllSettings() {
        context.dataStore.edit { it.clear() }
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

    // §C13 — preset to swap to when headphones connect. -1 = off.
    val headphoneEqPresetId: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.HEADPHONE_EQ_PRESET_ID] ?: -1L
    }

    suspend fun setHeadphoneEqPresetId(id: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HEADPHONE_EQ_PRESET_ID] = id
        }
    }

    /** §C13 — per-paired-device EQ. Map<address, presetId>. */
    val headphoneEqDevicePresets: Flow<Map<String, Long>> = context.dataStore.data
        .map { prefs ->
            (prefs[Keys.HEADPHONE_EQ_DEVICE_PRESETS] ?: emptySet())
                .mapNotNull {
                    val sep = it.indexOf('|')
                    if (sep <= 0) null
                    else {
                        val addr = it.substring(0, sep)
                        val pid = it.substring(sep + 1).toLongOrNull()
                        if (pid != null) addr to pid else null
                    }
                }
                .toMap()
        }

    suspend fun setHeadphoneEqDevicePreset(address: String, presetId: Long) {
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.HEADPHONE_EQ_DEVICE_PRESETS] ?: emptySet()
            val pruned = cur.filterNot { it.startsWith("$address|") }.toSet()
            prefs[Keys.HEADPHONE_EQ_DEVICE_PRESETS] = pruned + "$address|$presetId"
        }
    }

    // §11.5 — user-selectable accent colour. Default "" → falls back
    // to the hard-coded teal in TealAccent's holder.
    val themeAccentHex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_ACCENT_HEX] ?: ""
    }

    suspend fun setThemeAccentHex(hex: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_ACCENT_HEX] = hex
        }
    }

    // App-wide font / hitbox scale. Hybrid: text grows + touch targets
    // grow proportionally via LocalPmpScale; icon glyphs stay at design
    // size. Range 0.85f..2.0f, default 1.0f. Clamped at read time so a
    // stale value never breaks the layout.
    val fontSizeScale: Flow<Float> = context.dataStore.data.map { prefs ->
        (prefs[Keys.FONT_SIZE_SCALE] ?: 1.0f).coerceIn(0.85f, 2.0f)
    }

    suspend fun setFontSizeScale(value: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE_SCALE] = value.coerceIn(0.85f, 2.0f)
        }
    }

    /**
     * One read of the whole preferences file. DataStore keeps the parsed
     * state in memory after the first collection, so callers that need
     * several values synchronously (the playback service's cold-start
     * seeds) warm the store with ONE disk hit and every subsequent
     * `.first()` is a memory read — instead of N sequential cold reads
     * on the main thread (audit 2.1).
     */
    suspend fun snapshot(): androidx.datastore.preferences.core.Preferences =
        context.dataStore.data.first()

    // Opt-in persistent file logger (see DiagLog). Off by default. When
    // on, writes technical events (BT remote opcodes, audio-effect
    // attach results, key lifecycle) to app-private external storage so
    // testers can pull the file via adb or the system Files app.
    val diagLogEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DIAG_LOG_ENABLED] ?: false
    }

    suspend fun setDiagLogEnabled(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DIAG_LOG_ENABLED] = value
        }
    }

    // Manual video audio-offset for Bluetooth playback. Positive ms =
    // delay the video to catch up to the audio (typical BT). Negative
    // ms = the opposite. Range ±1000 ms, default 0. Applied to the
    // existing audio-delay path that the in-app Subtitle/Audio delay
    // setting already uses, so no new audio renderer plumbing needed.
    val btVideoAudioOffsetMs: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.BT_VIDEO_AUDIO_OFFSET_MS] ?: 0).coerceIn(-1000, 1000)
    }

    suspend fun setBtVideoAudioOffsetMs(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BT_VIDEO_AUDIO_OFFSET_MS] = value.coerceIn(-1000, 1000)
        }
    }

    // Reverb wet/dry mix — multiplies the EnvironmentalReverb reverbLevel
    // a preset would otherwise apply. 0.0 = effect inaudible (dry only),
    // 1.0 = full preset wetness (default). Lets the user dial back the
    // intensity without giving up the preset's other characteristics.
    val reverbWetMix: Flow<Float> = context.dataStore.data.map { prefs ->
        (prefs[Keys.REVERB_WET_MIX] ?: 1.0f).coerceIn(0f, 1f)
    }

    suspend fun setReverbWetMix(value: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REVERB_WET_MIX] = value.coerceIn(0f, 1f)
        }
    }

    // §C28 — Drive offline copies registry.
    val offlineDrivePairs: Flow<Map<String, String>> = context.dataStore.data
        .map { prefs ->
            (prefs[Keys.OFFLINE_DRIVE_PAIRS] ?: emptySet())
                .mapNotNull {
                    val sep = it.indexOf('|')
                    if (sep <= 0) null
                    else it.substring(0, sep) to it.substring(sep + 1)
                }
                .toMap()
        }

    suspend fun upsertOfflineDrive(id: String, absolutePath: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.OFFLINE_DRIVE_PAIRS] ?: emptySet()
            // Drop any prior pair for this id, then add the new one.
            val pruned = cur.filterNot { it.startsWith("$id|") }.toSet()
            prefs[Keys.OFFLINE_DRIVE_PAIRS] = pruned + "$id|$absolutePath"
        }
    }

    suspend fun removeOfflineDrive(id: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.OFFLINE_DRIVE_PAIRS] ?: emptySet()
            prefs[Keys.OFFLINE_DRIVE_PAIRS] =
                cur.filterNot { it.startsWith("$id|") }.toSet()
        }
    }

    /** §C28 — offline storage cap. 0 = unlimited; default 5 GB. */
    val offlineStorageLimitBytes: Flow<Long> = context.dataStore.data.map {
        it[Keys.OFFLINE_STORAGE_LIMIT_BYTES] ?: (5L * 1024 * 1024 * 1024)
    }
    suspend fun setOfflineStorageLimitBytes(bytes: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.OFFLINE_STORAGE_LIMIT_BYTES] = bytes }
    }

    /** §C18 — "track" or "album" gain mode. Default: track. */
    val replayGainMode: Flow<String> = context.dataStore.data.map {
        it[Keys.REPLAY_GAIN_MODE] ?: "track"
    }
    suspend fun setReplayGainMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[Keys.REPLAY_GAIN_MODE] = mode }
    }

    /** §C17 — enrichment sub-toggles + apply scope. */
    val enrichFetchArtwork: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ENRICH_FETCH_ARTWORK] ?: true
    }
    val enrichFetchYear: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ENRICH_FETCH_YEAR] ?: true
    }
    val enrichFetchGenre: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ENRICH_FETCH_GENRE] ?: true
    }
    val enrichApplyScope: Flow<String> = context.dataStore.data.map {
        it[Keys.ENRICH_APPLY_SCOPE] ?: "missing_only"
    }
    suspend fun setEnrichFetchArtwork(v: Boolean) =
        context.dataStore.edit { it[Keys.ENRICH_FETCH_ARTWORK] = v }.let { }
    suspend fun setEnrichFetchYear(v: Boolean) =
        context.dataStore.edit { it[Keys.ENRICH_FETCH_YEAR] = v }.let { }
    suspend fun setEnrichFetchGenre(v: Boolean) =
        context.dataStore.edit { it[Keys.ENRICH_FETCH_GENRE] = v }.let { }
    suspend fun setEnrichApplyScope(scope: String) =
        context.dataStore.edit { it[Keys.ENRICH_APPLY_SCOPE] = scope }.let { }

    /**
     * §C25 / A9 — per-file tag overrides. Stored as a Set<String> with
     * unit-separator () field delimiters so commas / pipes /
     * normal characters in titles can't collide with the encoding.
     */
    private val TAG_SEP = ""
    val tagOverrides: Flow<Map<String, Triple<String, String, String>>> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.TAG_OVERRIDES] ?: emptySet())
                .mapNotNull {
                    val parts = it.split(TAG_SEP)
                    if (parts.size < 4) null
                    else parts[0] to Triple(parts[1], parts[2], parts[3])
                }
                .toMap()
        }
    suspend fun upsertTagOverride(uri: String, title: String, artist: String, album: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.TAG_OVERRIDES] ?: emptySet()
            val pruned = cur.filterNot { it.startsWith("$uri$TAG_SEP") }.toSet()
            prefs[Keys.TAG_OVERRIDES] =
                pruned + "$uri$TAG_SEP$title$TAG_SEP$artist$TAG_SEP$album"
        }
    }
    suspend fun clearTagOverride(uri: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.TAG_OVERRIDES] ?: emptySet()
            prefs[Keys.TAG_OVERRIDES] = cur.filterNot { it.startsWith("$uri$TAG_SEP") }.toSet()
        }
    }

    val openSubsToken: Flow<String> = context.dataStore.data.map {
        it[Keys.OPENSUBS_TOKEN] ?: ""
    }
    val openSubsEmail: Flow<String> = context.dataStore.data.map {
        it[Keys.OPENSUBS_EMAIL] ?: ""
    }
    val openSubsApiKey: Flow<String> = context.dataStore.data.map {
        it[Keys.OPENSUBS_API_KEY] ?: ""
    }
    suspend fun setOpenSubsToken(t: String) {
        context.dataStore.edit { prefs -> prefs[Keys.OPENSUBS_TOKEN] = t }
    }
    suspend fun setOpenSubsEmail(e: String) {
        context.dataStore.edit { prefs -> prefs[Keys.OPENSUBS_EMAIL] = e }
    }
    suspend fun setOpenSubsApiKey(k: String) {
        context.dataStore.edit { prefs -> prefs[Keys.OPENSUBS_API_KEY] = k }
    }

    /** §C9 A2.2 — ISO-639-1 codes selected; default ["en"]. */
    val openSubsLanguages: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.OPENSUBS_LANGUAGES] ?: setOf("en")
    }
    suspend fun setOpenSubsLanguages(codes: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OPENSUBS_LANGUAGES] = if (codes.isEmpty()) setOf("en") else codes
        }
    }
    /** §C9 A2.3 — moviehash search vs filename query. */
    val openSubsMatchByHash: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.OPENSUBS_MATCH_BY_HASH] ?: false
    }
    suspend fun setOpenSubsMatchByHash(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.OPENSUBS_MATCH_BY_HASH] = v }
    }
    /** §C9 A2.4 — write the SRT next to the video file vs in app cache. */
    val openSubsSaveNextToVideo: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.OPENSUBS_SAVE_NEXT_TO_VIDEO] ?: false
    }
    suspend fun setOpenSubsSaveNextToVideo(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.OPENSUBS_SAVE_NEXT_TO_VIDEO] = v }
    }
    /** §C9 A2.5 — overwrite a sibling .srt instead of skipping. */
    val openSubsOverrideExisting: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.OPENSUBS_OVERRIDE_EXISTING] ?: false
    }
    suspend fun setOpenSubsOverrideExisting(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.OPENSUBS_OVERRIDE_EXISTING] = v }
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

    val artworkScaleMode: Flow<String> = context.dataStore.data.map {
        // Coerce unknown values back to "fit" so older / corrupt entries
        // never propagate an invalid ContentScale into the UI.
        when (val v = it[Keys.ARTWORK_SCALE_MODE]) {
            "fit", "fill" -> v
            else -> "fit"
        }
    }

    suspend fun setArtworkScaleMode(mode: String) {
        val coerced = if (mode == "fill") "fill" else "fit"
        context.dataStore.edit { prefs ->
            prefs[Keys.ARTWORK_SCALE_MODE] = coerced
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
