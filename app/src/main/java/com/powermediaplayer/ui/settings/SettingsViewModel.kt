package com.powermediaplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.preferences.BluetoothMediaActions
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Settings screen.
 */
data class SettingsUiState(
    val useDeepScan: Boolean = false,
    val subtitleFormat: String = "SRT",
    val useSoftwareDecoding: Boolean = false,
    val btPrevAction: String = BluetoothMediaActions.PREV_TRACK,
    val btNextAction: String = BluetoothMediaActions.NEXT_TRACK,
    val btSkipBackSeconds: Int = 30,
    val btSkipForwardSeconds: Int = 30,
    val videoFlipH: Boolean = false,
    val videoFlipV: Boolean = false,
    val videoBw: Boolean = false,
    val videoSepia: Boolean = false,
    val videoInvert: Boolean = false,
    val videoRotation: Int = 0,
    val audioReverseLocal: Boolean = false,
    val pitch: Float = 1.0f,
    val volumeBoostMb: Int = 0,
    val subtitleDelayMs: Int = 0,
    val audioDelayMs: Int = 0,
    val gaplessPlayback: Boolean = true,
    val replayGainEnabled: Boolean = false,
    val crossfadeMs: Int = 0,
    val resumeOnBt: Boolean = false,
    val prefetchNextCloud: Boolean = true,
    val reverbPreset: Int = 0,
    val stereoFlip: Boolean = false,
    val monoMix: Boolean = false,
    val passthroughAudio: Boolean = true,
    val artworkScaleMode: String = "fit",
    val videoControlsHideSec: Int = 4,
    val audioEffectsPopupHideSec: Int = 0,
    val videoEffectsPopupHideSec: Int = 0,
    val crossfadePopupHideSec: Int = 0,
    val infoSheetHideSec: Int = 0,
    val trackContextSheetHideSec: Int = 0,
    val audioControlsHideFoldedSec: Int = 0,
    val audioControlsHideTabletSec: Int = 0,
    val audioFocusOnCall: String = "pause",
    val audioFocusOnNotification: String = "duck",
    val audioFocusOnOtherMedia: String = "pause",
    val metadataEnrichmentEnabled: Boolean = false,
    val metadataEnrichmentProvider: String = "musicbrainz",
    val replayGainAutoScan: Boolean = false,
    val hiddenUris: Set<String> = emptySet(),
    // Crossfade (Phase 4 / §B2)
    val crossfadeEnabled: Boolean = false,
    val crossfadeCurve: String = "EQUAL_POWER",
    val crossfadeAlbumMode: Boolean = true,
    val crossfadeSkipSilence: Boolean = false,
    val crossfadePreFadeTriggerS: Int = 5,
    val crossfadeManualFadeNowEnabled: Boolean = false,
    val crossfadeFadeOutOnPause: Boolean = false,
    val crossfadeFadeInOnResume: Boolean = false,
    val headphonePlugAutoplay: Boolean = false,
    val sleepTimerFadeOut: Boolean = false,
    val taskerIntentsEnabled: Boolean = false,
    val bookmarkReplayContextSec: Int = 10,
    val stopOnTaskRemoved: Boolean = false,
    val coldStartResumeBackoffSec: Int = 5,
    val scheduledAlarms: List<com.powermediaplayer.alarm.AlarmRecord> = emptyList(),
    val themeAccentHex: String = "",
    val fontSizeScale: Float = 1.0f,
    val diagLogEnabled: Boolean = false,
    val btVideoAudioOffsetMs: Int = 0,
    val reverbWetMix: Float = 1.0f,
    val audioBufferLowLatency: Boolean = false,
    val webhookUrl: String = "",
    val webhookOnPlay: Boolean = false,
    val webhookOnPause: Boolean = false,
    val webhookOnResume: Boolean = false,
    val webhookOnSkipNext: Boolean = false,
    val webhookOnSkipPrev: Boolean = false,
    val webhookOnEnd: Boolean = false,
    val hueBridgeIp: String = "",
    val hueAppKey: String = "",
    val huePairStatus: String = ""
)

/**
 * ViewModel for the Settings screen.
 * Reads and writes user preferences via SettingsDataStore.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settingsDataStore: SettingsDataStore,
    private val hueProvider: com.powermediaplayer.hue.HueProvider,
    private val webhookEmitter: com.powermediaplayer.webhooks.WebhookEmitter,
    private val audioOutputDetector: com.powermediaplayer.audio.AudioOutputDetector,
    val playbackHistoryDao: com.powermediaplayer.data.db.dao.PlaybackHistoryDao,
    private val spotifyTokenStore: com.powermediaplayer.cloud.SpotifyTokenStore,
    private val openSubsCredStore: com.powermediaplayer.subtitles.OpenSubsCredStore,
    private val driveProvider: com.powermediaplayer.cloud.DriveOAuthProvider
) : ViewModel() {

    /**
     * Mirrors [AudioOutputDetector.isTrueMonoOutput] so the
     * AudioEffectsButton can disable Stereo flip / Mono mix toggles
     * when the active output is a true mono speaker (the toggles
     * have no audible effect there).
     */
    val isTrueMonoOutput: StateFlow<Boolean> = audioOutputDetector.isTrueMonoOutput

    val uiState: StateFlow<SettingsUiState> = combine(
        listOf<Flow<Any>>(
            settingsDataStore.useDeepScan,
            settingsDataStore.subtitleFormat,
            settingsDataStore.useSoftwareDecoding,
            settingsDataStore.btPrevAction,
            settingsDataStore.btNextAction,
            settingsDataStore.btSkipBackSeconds,
            settingsDataStore.btSkipForwardSeconds,
            settingsDataStore.videoFlipH,
            settingsDataStore.videoFlipV,
            settingsDataStore.videoBw,
            settingsDataStore.videoRotation,
            settingsDataStore.audioReverseLocal,
            settingsDataStore.pitchIndependent,
            settingsDataStore.volumeBoostMb,
            settingsDataStore.subtitleDelayMs,
            settingsDataStore.audioDelayMs,
            settingsDataStore.gaplessPlayback,
            settingsDataStore.replayGainEnabled,
            settingsDataStore.crossfadeMs,
            settingsDataStore.resumeOnBt,
            settingsDataStore.prefetchNextCloud,
            settingsDataStore.reverbPreset,
            settingsDataStore.stereoFlip,
            settingsDataStore.monoMix,
            settingsDataStore.passthroughAudio,
            settingsDataStore.videoSepia,
            settingsDataStore.videoInvert,
            settingsDataStore.artworkScaleMode,
            settingsDataStore.videoControlsHideSec,
            settingsDataStore.audioEffectsPopupHideSec,
            settingsDataStore.videoEffectsPopupHideSec,
            settingsDataStore.hiddenUris,
            settingsDataStore.crossfadeEnabled,
            settingsDataStore.crossfadeCurve,
            settingsDataStore.crossfadeAlbumMode,
            settingsDataStore.crossfadeSkipSilence,
            settingsDataStore.crossfadePreFadeTriggerS,
            settingsDataStore.crossfadeManualFadeNowEnabled,
            settingsDataStore.crossfadeFadeOutOnPause,
            settingsDataStore.crossfadeFadeInOnResume,
            settingsDataStore.headphonePlugAutoplay,
            settingsDataStore.sleepTimerFadeOut,
            settingsDataStore.taskerIntentsEnabled,
            settingsDataStore.bookmarkReplayContextSec,
            settingsDataStore.stopOnTaskRemoved,
            settingsDataStore.coldStartResumeBackoffSec,
            settingsDataStore.crossfadePopupHideSec,
            settingsDataStore.infoSheetHideSec,
            settingsDataStore.trackContextSheetHideSec,
            settingsDataStore.audioControlsHideFoldedSec,
            settingsDataStore.audioControlsHideTabletSec,
            settingsDataStore.audioFocusOnCall,
            settingsDataStore.audioFocusOnNotification,
            settingsDataStore.audioFocusOnOtherMedia,
            settingsDataStore.metadataEnrichmentEnabled,
            settingsDataStore.metadataEnrichmentProvider,
            settingsDataStore.replayGainAutoScan,
            settingsDataStore.scheduledAlarms,
            settingsDataStore.themeAccentHex,
            settingsDataStore.fontSizeScale,
            settingsDataStore.diagLogEnabled,
            settingsDataStore.btVideoAudioOffsetMs,
            settingsDataStore.reverbWetMix,
            settingsDataStore.audioBufferLowLatency,
            settingsDataStore.webhookUrl,
            settingsDataStore.webhookOnPlay,
            settingsDataStore.webhookOnPause,
            settingsDataStore.webhookOnResume,
            settingsDataStore.webhookOnSkipNext,
            settingsDataStore.webhookOnSkipPrev,
            settingsDataStore.webhookOnEnd,
            settingsDataStore.hueBridgeIp,
            settingsDataStore.hueAppKey
        )
    ) { v ->
        SettingsUiState(
            useDeepScan = v[0] as Boolean,
            subtitleFormat = v[1] as String,
            useSoftwareDecoding = v[2] as Boolean,
            btPrevAction = v[3] as String,
            btNextAction = v[4] as String,
            btSkipBackSeconds = v[5] as Int,
            btSkipForwardSeconds = v[6] as Int,
            videoFlipH = v[7] as Boolean,
            videoFlipV = v[8] as Boolean,
            videoBw = v[9] as Boolean,
            videoRotation = v[10] as Int,
            audioReverseLocal = v[11] as Boolean,
            pitch = v[12] as Float,
            volumeBoostMb = v[13] as Int,
            subtitleDelayMs = v[14] as Int,
            audioDelayMs = v[15] as Int,
            gaplessPlayback = v[16] as Boolean,
            replayGainEnabled = v[17] as Boolean,
            crossfadeMs = v[18] as Int,
            resumeOnBt = v[19] as Boolean,
            prefetchNextCloud = v[20] as Boolean,
            reverbPreset = v[21] as Int,
            stereoFlip = v[22] as Boolean,
            monoMix = v[23] as Boolean,
            passthroughAudio = v[24] as Boolean,
            videoSepia = v[25] as Boolean,
            videoInvert = v[26] as Boolean,
            artworkScaleMode = v[27] as String,
            videoControlsHideSec = v[28] as Int,
            audioEffectsPopupHideSec = v[29] as Int,
            videoEffectsPopupHideSec = v[30] as Int,
            hiddenUris = @Suppress("UNCHECKED_CAST") (v[31] as Set<String>),
            crossfadeEnabled = v[32] as Boolean,
            crossfadeCurve = v[33] as String,
            crossfadeAlbumMode = v[34] as Boolean,
            crossfadeSkipSilence = v[35] as Boolean,
            crossfadePreFadeTriggerS = v[36] as Int,
            crossfadeManualFadeNowEnabled = v[37] as Boolean,
            crossfadeFadeOutOnPause = v[38] as Boolean,
            crossfadeFadeInOnResume = v[39] as Boolean,
            headphonePlugAutoplay = v[40] as Boolean,
            sleepTimerFadeOut = v[41] as Boolean,
            taskerIntentsEnabled = v[42] as Boolean,
            bookmarkReplayContextSec = v[43] as Int,
            stopOnTaskRemoved = v[44] as Boolean,
            coldStartResumeBackoffSec = v[45] as Int,
            crossfadePopupHideSec = v[46] as Int,
            infoSheetHideSec = v[47] as Int,
            trackContextSheetHideSec = v[48] as Int,
            audioControlsHideFoldedSec = v[49] as Int,
            audioControlsHideTabletSec = v[50] as Int,
            audioFocusOnCall = v[51] as String,
            audioFocusOnNotification = v[52] as String,
            audioFocusOnOtherMedia = v[53] as String,
            metadataEnrichmentEnabled = v[54] as Boolean,
            metadataEnrichmentProvider = v[55] as String,
            replayGainAutoScan = v[56] as Boolean,
            scheduledAlarms = @Suppress("UNCHECKED_CAST") (v[57] as List<com.powermediaplayer.alarm.AlarmRecord>),
            themeAccentHex = v[58] as String,
            fontSizeScale = v[59] as Float,
            diagLogEnabled = v[60] as Boolean,
            btVideoAudioOffsetMs = v[61] as Int,
            reverbWetMix = v[62] as Float,
            audioBufferLowLatency = v[63] as Boolean,
            webhookUrl = v[64] as String,
            webhookOnPlay = v[65] as Boolean,
            webhookOnPause = v[66] as Boolean,
            webhookOnResume = v[67] as Boolean,
            webhookOnSkipNext = v[68] as Boolean,
            webhookOnSkipPrev = v[69] as Boolean,
            webhookOnEnd = v[70] as Boolean,
            hueBridgeIp = v[71] as String,
            hueAppKey = v[72] as String
        )
    }
        // vc29.26 — drop duplicate emissions + conflate rapid bursts.
        // The full uiState rebuilds on EVERY one of 73 DataStore flows;
        // sliders writing back can fire 10+ values in quick succession.
        // distinctUntilChanged elides re-emissions when the data class
        // didn't actually change (data class equals is structural).
        // conflate drops intermediate values so the UI only sees the
        // latest state, eliminating mid-flight redrawn invalidations.
        .distinctUntilChanged()
        .conflate()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsUiState()
        )

    fun setDeepScan(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDeepScan(enabled) }
    }

    fun setFontSizeScale(value: Float) {
        viewModelScope.launch { settingsDataStore.setFontSizeScale(value) }
    }

    fun setDiagLogEnabled(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setDiagLogEnabled(value) }
    }

    fun setBtVideoAudioOffsetMs(value: Int) {
        viewModelScope.launch { settingsDataStore.setBtVideoAudioOffsetMs(value) }
    }

    fun setReverbWetMix(value: Float) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui(
                "settings reverbWetMix=${"%.3f".format(value)} (instant — AuxEffectInfo)"
            )
            settingsDataStore.setReverbWetMix(value)
        }
    }

    fun setAudioBufferLowLatency(v: Boolean) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("settings audioBufferLowLatency=$v")
            settingsDataStore.setAudioBufferLowLatency(v)
        }
    }

    fun setWebhookUrl(v: String) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings webhookUrl=(set, len=${v.length})")
        settingsDataStore.setWebhookUrl(v)
    }.let {}
    fun setWebhookOnPlay(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings webhookOnPlay=$v")
        settingsDataStore.setWebhookOnPlay(v)
    }.let {}
    fun setWebhookOnPause(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings webhookOnPause=$v")
        settingsDataStore.setWebhookOnPause(v)
    }.let {}
    fun setWebhookOnResume(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings webhookOnResume=$v")
        settingsDataStore.setWebhookOnResume(v)
    }.let {}
    fun setWebhookOnSkipNext(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings webhookOnSkipNext=$v")
        settingsDataStore.setWebhookOnSkipNext(v)
    }.let {}
    fun setWebhookOnSkipPrev(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings webhookOnSkipPrev=$v")
        settingsDataStore.setWebhookOnSkipPrev(v)
    }.let {}
    fun setWebhookOnEnd(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings webhookOnEnd=$v")
        settingsDataStore.setWebhookOnEnd(v)
    }.let {}

    private val _webhookTestStatus = kotlinx.coroutines.flow.MutableStateFlow("")
    val webhookTestStatus: kotlinx.coroutines.flow.StateFlow<String> = _webhookTestStatus

    /**
     * Fire one synthetic webhook event to the configured URL so the
     * user can confirm their endpoint accepts our payload shape. Result
     * routed via [webhookTestStatus] — Settings screen surfaces under
     * the URL field.
     */
    fun testWebhook() {
        viewModelScope.launch {
            _webhookTestStatus.value = "Sending test…"
            val res = webhookEmitter.fireTestSync()
            _webhookTestStatus.value = res.fold(
                onSuccess = { code -> "Test sent — endpoint replied HTTP $code" },
                onFailure = { "Test failed — ${it.message}" }
            )
        }
    }

    // ── Hue v1 ─────────────────────────────────────────────────────
    private val _huePairStatus = kotlinx.coroutines.flow.MutableStateFlow("")
    val huePairStatus: kotlinx.coroutines.flow.StateFlow<String> = _huePairStatus

    /** IP of the discovered bridge held between Discover → Pair taps.
     *  Empty until Discover succeeds; cleared when pair completes
     *  (success or final give-up). */
    private val _hueDiscoveredIp = kotlinx.coroutines.flow.MutableStateFlow("")
    val hueDiscoveredIp: kotlinx.coroutines.flow.StateFlow<String> = _hueDiscoveredIp

    /**
     * Two-step pair flow — step 1 of 2.
     * Find the bridge IP via Philips cloud N-UPnP. On success, hold the
     * IP in [_hueDiscoveredIp] and show a clear instruction to press
     * the physical button on the bridge. User then taps "Pair" which
     * calls [completeHuePair] within the bridge's 30 s window.
     *
     * Why two steps: the bridge only accepts a pair request for 30 s
     * AFTER its physical button is pressed. One-tap "discover + pair"
     * always fires the pair POST before the user can read the
     * instruction and walk to the bridge, so we always got the
     * "link button not pressed" rejection.
     */
    fun discoverHueBridge() {
        viewModelScope.launch {
            _huePairStatus.value = "Searching for bridge on your Wi-Fi…"
            val bridges = hueProvider.discover()
            if (bridges.isEmpty()) {
                _huePairStatus.value = "No bridge found on this Wi-Fi. " +
                    "Check the phone and bridge are on the same network, " +
                    "or enter the bridge IP manually below."
                _hueDiscoveredIp.value = ""
                return@launch
            }
            val first = bridges.first()
            _hueDiscoveredIp.value = first.ip
            _huePairStatus.value = "Found bridge at ${first.ip}. " +
                "Now press the round button on top of your Hue bridge " +
                "(the one with the three lights), then tap Pair within 30 seconds."
        }
    }

    /**
     * Two-step pair flow — step 2 of 2.
     * Called after the user has discovered the bridge AND pressed its
     * physical button. Uses [_hueDiscoveredIp] (or [manualIp] if the
     * user typed an IP). On bridge "link button not pressed" the
     * status updates clearly so the user can press the button and
     * retap without confusion.
     */
    fun completeHuePair(manualIp: String = "") {
        viewModelScope.launch {
            val ip = manualIp.ifBlank { _hueDiscoveredIp.value }
            if (ip.isBlank()) {
                _huePairStatus.value = "Tap Discover first, or type the bridge IP."
                return@launch
            }
            _huePairStatus.value = "Pairing with $ip…"
            val key = hueProvider.pair(ip)
            if (key != null) {
                _huePairStatus.value = "Paired with bridge $ip ✓"
                _hueDiscoveredIp.value = ""
            } else {
                _huePairStatus.value = "Bridge says button not pressed. " +
                    "Press the round button on top of the bridge, then " +
                    "tap Pair again within 30 seconds."
            }
        }
    }

    /** Legacy entry point — keep for backward call sites; routes via the
     *  new two-step path. */
    fun pairHueBridge() = discoverHueBridge()

    fun applyHueScene(preset: com.powermediaplayer.hue.HueProvider.ScenePreset) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("hue applyScene=${preset.name}")
            hueProvider.applyScene(preset)
        }
    }

    fun setHueAll(on: Boolean) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("hue setAll(on=$on)")
            hueProvider.setAll(on)
        }
    }

    fun unpairHue() {
        viewModelScope.launch {
            settingsDataStore.setHueAppKey("")
            settingsDataStore.setHueBridgeIp("")
            settingsDataStore.setHueClientKey("")
            settingsDataStore.setHueEntertainmentId("")
            settingsDataStore.setHueReactiveMode("off")
            // Also stop the engine immediately + drop the area pick +
            // intensity so we don't try to resume against a now-blank
            // config the next time playback starts.
            settingsDataStore.setHueReactiveIntensity(0)
            settingsDataStore.setHueSelectedArea("")
            _hueAreas.value = emptyList()
            _huePairStatus.value = ""
        }
    }

    /** Clear the picked area without unpairing the bridge. */
    fun clearHueSelectedArea() {
        viewModelScope.launch {
            // vc31 — instrument the disconnect side of the reconnect
            // regression. Logs the prior selection + intensity so the
            // on-device trace shows the exact transition the engine
            // then has to recover from.
            val priorArea = runCatching { settingsDataStore.hueSelectedArea.first() }.getOrDefault("?")
            val priorIntensity = runCatching { settingsDataStore.hueReactiveIntensity.first() }.getOrDefault(-1)
            com.powermediaplayer.diag.DiagLog.event(
                "HUE",
                "DISCONNECT room/zone — priorArea=$priorArea priorIntensity=$priorIntensity " +
                    "→ clearing area (intensity PRESERVED, vc32 T253)"
            )
            // vc32 (T253): do NOT zero the sensitivity. Zeroing made every
            // re-pick dead (the collector saw intensity=0 forever — logged
            // 22:07:41) and silently reset a user preference whose slider
            // now lives in the collapsed Tuning sub-section. The stream
            // still stops on disconnect: the collector treats a BLANK area
            // as off (PlaybackService gate, same change-set).
            settingsDataStore.setHueSelectedArea("")
        }
    }

    fun setHueReactiveMode(mode: com.powermediaplayer.hue.HueEntertainment.ReactiveMode) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("hue reactiveMode=${mode.key}")
            settingsDataStore.setHueReactiveMode(mode.key)
        }
    }

    fun setHueReactiveIntensity(v: Int) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("hue reactiveIntensity=$v")
            settingsDataStore.setHueReactiveIntensity(v)
        }
    }

    fun setHueSyncOffsetMs(v: Int) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("hue syncOffsetMs=$v")
            settingsDataStore.setHueSyncOffsetMs(v)
        }
    }

    // vc29 — area picker. Each entry exposes the area's id + kind +
    // capability tier counts so the UI can show e.g.
    // "Living Area (room) — 8 colour, 4 ambiance, 4 dimmable, 2 plugs".
    data class HueAreaSummary(
        val key: String,        // "<kind>:<uuid>" composite for DataStore
        val displayName: String,
        val kind: com.powermediaplayer.hue.HueProvider.HueArea.Kind,
        val colour: Int,
        val ambiance: Int,
        val dimmable: Int,
        val onoff: Int
    )

    private val _hueAreas = MutableStateFlow<List<HueAreaSummary>>(emptyList())
    val hueAreas: StateFlow<List<HueAreaSummary>> = _hueAreas

    // vc29 — observable so the Settings UI shows a spinner while
    // listAreas/listAllLights are in flight (without it the Refresh
    // tap had no visible effect, leaving users unsure whether the
    // network call ran).
    private val _isRefreshingHueAreas = MutableStateFlow(false)
    val isRefreshingHueAreas: StateFlow<Boolean> = _isRefreshingHueAreas

    /** One-shot refresh of the area picker list. */
    fun refreshHueAreas() {
        // Don't stack refreshes — the spinner stays up for the in-flight
        // request and subsequent taps are no-ops until it completes.
        if (_isRefreshingHueAreas.value) return
        viewModelScope.launch {
            _isRefreshingHueAreas.value = true
            try {
                val lights = runCatching { hueProvider.listAllLights() }
                    .getOrDefault(emptyMap())
                val areas = runCatching { hueProvider.listAreas() }
                    .getOrDefault(emptyList())
                _hueAreas.value = areas.map { area ->
                    val b = hueProvider.classifyArea(area, lights)
                    val kindTok = when (area.kind) {
                        com.powermediaplayer.hue.HueProvider.HueArea.Kind.ROOM -> "room"
                        com.powermediaplayer.hue.HueProvider.HueArea.Kind.ZONE -> "zone"
                        com.powermediaplayer.hue.HueProvider.HueArea.Kind.ENTERTAINMENT -> "ent"
                    }
                    HueAreaSummary(
                        key = "$kindTok:${area.id}",
                        displayName = area.name,
                        kind = area.kind,
                        colour = b.colour.size,
                        ambiance = b.ambiance.size,
                        dimmable = b.dimmable.size,
                        onoff = b.onoff.size
                    )
                }
            } finally {
                _isRefreshingHueAreas.value = false
            }
        }
    }

    fun setHueSelectedArea(key: String) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("hue selectedArea=$key")
            settingsDataStore.setHueSelectedArea(key)
        }
    }

    fun setHueSpreadBands(v: Boolean) {
        viewModelScope.launch { settingsDataStore.setHueSpreadBands(v) }
    }

    fun setHueDriveDimmable(v: Boolean) {
        viewModelScope.launch { settingsDataStore.setHueDriveDimmable(v) }
    }

    fun setHueDimmableLagOffsetMs(v: Int) {
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("hue dimmableLagOffset=$v ms")
            settingsDataStore.setHueDimmableLagOffsetMs(v)
        }
    }

    fun clearDiagLog() {
        com.powermediaplayer.diag.DiagLog.clear()
    }

    fun diagLogPath(): String = com.powermediaplayer.diag.DiagLog.directoryPath()
    fun diagLogBytes(): Long = com.powermediaplayer.diag.DiagLog.totalBytes()

    fun setVideoControlsHideSec(seconds: Int) {
        viewModelScope.launch { settingsDataStore.setVideoControlsHideSec(seconds) }
    }

    fun setAudioEffectsPopupHideSec(seconds: Int) {
        viewModelScope.launch { settingsDataStore.setAudioEffectsPopupHideSec(seconds) }
    }

    fun setVideoEffectsPopupHideSec(seconds: Int) {
        viewModelScope.launch { settingsDataStore.setVideoEffectsPopupHideSec(seconds) }
    }

    fun unhideUri(uri: String) {
        viewModelScope.launch { settingsDataStore.unhideUri(uri) }
    }

    fun unhideAll() {
        viewModelScope.launch { settingsDataStore.unhideAll() }
    }

    // ── Crossfade setters (Phase 4) ─────────────────────────────────
    fun setCrossfadeEnabled(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setCrossfadeEnabled(v) }.let { }
    fun setCrossfadeMs(v: Int) =
        viewModelScope.launch {
            com.powermediaplayer.diag.DiagLog.ui("settings crossfadeMs=$v (applies on next track transition)")
            settingsDataStore.setCrossfadeMs(v)
        }.let { }
    fun setCrossfadeCurve(v: String) =
        viewModelScope.launch { settingsDataStore.setCrossfadeCurve(v) }.let { }
    fun setCrossfadeAlbumMode(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setCrossfadeAlbumMode(v) }.let { }
    fun setCrossfadeSkipSilence(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setCrossfadeSkipSilence(v) }.let { }
    fun setCrossfadePreFadeTriggerS(v: Int) =
        viewModelScope.launch { settingsDataStore.setCrossfadePreFadeTriggerS(v) }.let { }
    fun setCrossfadeManualFadeNowEnabled(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setCrossfadeManualFadeNowEnabled(v) }.let { }
    fun setCrossfadeFadeOutOnPause(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setCrossfadeFadeOutOnPause(v) }.let { }
    fun setCrossfadeFadeInOnResume(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setCrossfadeFadeInOnResume(v) }.let { }

    fun setHeadphonePlugAutoplay(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setHeadphonePlugAutoplay(v) }.let { }

    fun setSleepTimerFadeOut(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setSleepTimerFadeOut(v) }.let { }

    fun setTaskerIntentsEnabled(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setTaskerIntentsEnabled(v) }.let { }

    fun setBookmarkReplayContextSec(v: Int) =
        viewModelScope.launch { settingsDataStore.setBookmarkReplayContextSec(v) }.let { }

    fun setStopOnTaskRemoved(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setStopOnTaskRemoved(v) }.let { }

    fun setThemeAccentHex(hex: String) =
        viewModelScope.launch { settingsDataStore.setThemeAccentHex(hex) }.let { }

    fun setColdStartResumeBackoffSec(v: Int) =
        viewModelScope.launch { settingsDataStore.setColdStartResumeBackoffSec(v) }.let { }

    fun setCrossfadePopupHideSec(v: Int) =
        viewModelScope.launch { settingsDataStore.setCrossfadePopupHideSec(v) }.let { }

    fun setInfoSheetHideSec(v: Int) =
        viewModelScope.launch { settingsDataStore.setInfoSheetHideSec(v) }.let { }

    fun setTrackContextSheetHideSec(v: Int) =
        viewModelScope.launch { settingsDataStore.setTrackContextSheetHideSec(v) }.let { }

    fun setAudioControlsHideFoldedSec(v: Int) =
        viewModelScope.launch { settingsDataStore.setAudioControlsHideFoldedSec(v) }.let { }

    fun setAudioControlsHideTabletSec(v: Int) =
        viewModelScope.launch { settingsDataStore.setAudioControlsHideTabletSec(v) }.let { }

    fun setAudioFocusOnCall(token: String) =
        viewModelScope.launch { settingsDataStore.setAudioFocusOnCall(token) }.let { }
    fun setAudioFocusOnNotification(token: String) =
        viewModelScope.launch { settingsDataStore.setAudioFocusOnNotification(token) }.let { }
    fun setAudioFocusOnOtherMedia(token: String) =
        viewModelScope.launch { settingsDataStore.setAudioFocusOnOtherMedia(token) }.let { }

    fun setMetadataEnrichmentEnabled(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setMetadataEnrichmentEnabled(v) }.let { }
    fun setMetadataEnrichmentProvider(v: String) =
        viewModelScope.launch { settingsDataStore.setMetadataEnrichmentProvider(v) }.let { }

    fun setReplayGainAutoScan(v: Boolean) =
        viewModelScope.launch { settingsDataStore.setReplayGainAutoScan(v) }.let { }

    /**
     * Wipe DataStore (settings) + Spotify auth state + OpenSubtitles
     * credentials. User-reported "reset doesn't truly reset everything"
     * was the OAuth tokens surviving — those live in their own
     * preferences files outside the main settings DataStore.
     */
    fun resetAllSettings() =
        viewModelScope.launch {
            settingsDataStore.resetAllSettings()
            runCatching { spotifyTokenStore.write(null) }
            runCatching { openSubsCredStore.clear() }
            // Drive OAuth lives in Google Sign-In's own account cache,
            // not in our DataStore. Without an explicit signOut() the
            // Cloud tab still shows the user as signed-in to Drive
            // after a "Reset all".
            runCatching { driveProvider.signOut() }
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "resetAllSettings: cleared DataStore + Spotify + OpenSubs + Drive OAuth"
            )
        }.let { }

    fun setSubtitleFormat(format: String) {
        viewModelScope.launch { settingsDataStore.setSubtitleFormat(format) }
    }

    fun setSoftwareDecoding(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setSoftwareDecoding(enabled) }
    }

    fun setBtPrevAction(action: String) {
        viewModelScope.launch { settingsDataStore.setBtPrevAction(action) }
    }

    fun setBtNextAction(action: String) {
        viewModelScope.launch { settingsDataStore.setBtNextAction(action) }
    }

    fun setBtSkipBackSeconds(seconds: Int) {
        viewModelScope.launch { settingsDataStore.setBtSkipBackSeconds(seconds) }
    }

    fun setBtSkipForwardSeconds(seconds: Int) {
        viewModelScope.launch { settingsDataStore.setBtSkipForwardSeconds(seconds) }
    }

    // Video effects: applied per-frame in the GL surface (~16 ms floor
    // at 60 Hz). Effectively instant — DataStore→Flow→@Volatile then
    // next frame.
    fun setVideoFlipH(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings videoFlipH=$v")
        settingsDataStore.setVideoFlipH(v)
    }.let{}
    fun setVideoFlipV(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings videoFlipV=$v")
        settingsDataStore.setVideoFlipV(v)
    }.let{}
    fun setVideoBw(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings videoBw=$v")
        settingsDataStore.setVideoBw(v)
    }.let{}
    fun setVideoSepia(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings videoSepia=$v")
        settingsDataStore.setVideoSepia(v)
    }.let{}
    fun setVideoInvert(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings videoInvert=$v")
        settingsDataStore.setVideoInvert(v)
    }.let{}
    fun setVideoRotation(d: Int) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings videoRotation=${d}deg")
        settingsDataStore.setVideoRotation(d)
    }.let{}
    fun setAudioReverseLocal(v: Boolean) = viewModelScope.launch { settingsDataStore.setAudioReverseLocal(v) }.let{}
    fun setPitch(p: Float) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings pitch=$p (Settings-side; routed via DataStore Flow)")
        settingsDataStore.setPitchIndependent(p)
    }.let{}
    fun setVolumeBoost(mb: Int) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings volumeBoost=${mb}mB")
        settingsDataStore.setVolumeBoostMb(mb)
    }.let{}
    fun setSubtitleDelay(ms: Int) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings subtitleDelay=${ms}ms")
        settingsDataStore.setSubtitleDelayMs(ms)
    }.let{}
    fun setAudioDelay(ms: Int) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings audioDelay=${ms}ms")
        settingsDataStore.setAudioDelayMs(ms)
    }.let{}
    fun setGapless(v: Boolean) = viewModelScope.launch { settingsDataStore.setGaplessPlayback(v) }.let{}
    fun setReverbPreset(p: Int) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings reverbPreset=$p")
        settingsDataStore.setReverbPreset(p)
    }.let{}
    fun setStereoFlip(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings stereoFlip=$v")
        settingsDataStore.setStereoFlip(v)
    }.let{}
    fun setMonoMix(v: Boolean) = viewModelScope.launch {
        com.powermediaplayer.diag.DiagLog.ui("settings monoMix=$v")
        settingsDataStore.setMonoMix(v)
    }.let{}
    fun setPassthroughAudio(v: Boolean) = viewModelScope.launch { settingsDataStore.setPassthroughAudio(v) }.let{}
    fun setReplayGain(v: Boolean) = viewModelScope.launch { settingsDataStore.setReplayGainEnabled(v) }.let{}
    fun setCrossfade(ms: Int) = viewModelScope.launch { settingsDataStore.setCrossfadeMs(ms) }.let{}
    fun setResumeOnBt(v: Boolean) = viewModelScope.launch { settingsDataStore.setResumeOnBt(v) }.let{}
    fun setPrefetchNextCloud(v: Boolean) = viewModelScope.launch { settingsDataStore.setPrefetchNextCloud(v) }.let{}
    fun setArtworkScaleMode(mode: String) = viewModelScope.launch { settingsDataStore.setArtworkScaleMode(mode) }.let{}
}
