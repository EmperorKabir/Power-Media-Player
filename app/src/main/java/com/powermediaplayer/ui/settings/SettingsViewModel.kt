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
    val audioEffectsPopupHideSec: Int = 3,
    val videoEffectsPopupHideSec: Int = 3,
    val hiddenUris: Set<String> = emptySet(),
    // Crossfade (Phase 4 / §B2)
    val crossfadeEnabled: Boolean = false,
    val crossfadeCurve: String = "EQUAL_POWER",
    val crossfadeAlbumMode: Boolean = true,
    val crossfadeSkipSilence: Boolean = false,
    val crossfadePreFadeTriggerS: Int = 5,
    val crossfadeManualFadeNowEnabled: Boolean = false,
    val crossfadeFadeOutOnPause: Boolean = false,
    val crossfadeFadeInOnResume: Boolean = false
)

/**
 * ViewModel for the Settings screen.
 * Reads and writes user preferences via SettingsDataStore.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val audioOutputDetector: com.powermediaplayer.audio.AudioOutputDetector
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
            settingsDataStore.crossfadeFadeInOnResume
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
            crossfadeFadeInOnResume = v[39] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setDeepScan(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDeepScan(enabled) }
    }

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
        viewModelScope.launch { settingsDataStore.setCrossfadeMs(v) }.let { }
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

    fun setVideoFlipH(v: Boolean) = viewModelScope.launch { settingsDataStore.setVideoFlipH(v) }.let{}
    fun setVideoFlipV(v: Boolean) = viewModelScope.launch { settingsDataStore.setVideoFlipV(v) }.let{}
    fun setVideoBw(v: Boolean) = viewModelScope.launch { settingsDataStore.setVideoBw(v) }.let{}
    fun setVideoSepia(v: Boolean) = viewModelScope.launch { settingsDataStore.setVideoSepia(v) }.let{}
    fun setVideoInvert(v: Boolean) = viewModelScope.launch { settingsDataStore.setVideoInvert(v) }.let{}
    fun setVideoRotation(d: Int) = viewModelScope.launch { settingsDataStore.setVideoRotation(d) }.let{}
    fun setAudioReverseLocal(v: Boolean) = viewModelScope.launch { settingsDataStore.setAudioReverseLocal(v) }.let{}
    fun setPitch(p: Float) = viewModelScope.launch { settingsDataStore.setPitchIndependent(p) }.let{}
    fun setVolumeBoost(mb: Int) = viewModelScope.launch { settingsDataStore.setVolumeBoostMb(mb) }.let{}
    fun setSubtitleDelay(ms: Int) = viewModelScope.launch { settingsDataStore.setSubtitleDelayMs(ms) }.let{}
    fun setAudioDelay(ms: Int) = viewModelScope.launch { settingsDataStore.setAudioDelayMs(ms) }.let{}
    fun setGapless(v: Boolean) = viewModelScope.launch { settingsDataStore.setGaplessPlayback(v) }.let{}
    fun setReverbPreset(p: Int) = viewModelScope.launch { settingsDataStore.setReverbPreset(p) }.let{}
    fun setStereoFlip(v: Boolean) = viewModelScope.launch { settingsDataStore.setStereoFlip(v) }.let{}
    fun setMonoMix(v: Boolean) = viewModelScope.launch { settingsDataStore.setMonoMix(v) }.let{}
    fun setPassthroughAudio(v: Boolean) = viewModelScope.launch { settingsDataStore.setPassthroughAudio(v) }.let{}
    fun setReplayGain(v: Boolean) = viewModelScope.launch { settingsDataStore.setReplayGainEnabled(v) }.let{}
    fun setCrossfade(ms: Int) = viewModelScope.launch { settingsDataStore.setCrossfadeMs(ms) }.let{}
    fun setResumeOnBt(v: Boolean) = viewModelScope.launch { settingsDataStore.setResumeOnBt(v) }.let{}
    fun setPrefetchNextCloud(v: Boolean) = viewModelScope.launch { settingsDataStore.setPrefetchNextCloud(v) }.let{}
    fun setArtworkScaleMode(mode: String) = viewModelScope.launch { settingsDataStore.setArtworkScaleMode(mode) }.let{}
}
