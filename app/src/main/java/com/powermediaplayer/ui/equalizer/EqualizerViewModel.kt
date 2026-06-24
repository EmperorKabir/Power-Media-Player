package com.powermediaplayer.ui.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.powermediaplayer.data.db.dao.EqualizerPresetDao
import com.powermediaplayer.data.db.entity.EqualizerPresetEntity
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the 10-band equalizer screen.
 */
data class EqualizerUiState(
    val bandLevels: List<Int> = List(10) { 0 }, // Each band in millibels
    val bandFrequencies: List<String> = listOf(
        "31Hz", "63Hz", "125Hz", "250Hz", "500Hz",
        "1kHz", "2kHz", "4kHz", "8kHz", "16kHz"
    ),
    val minLevel: Int = -1500, // -15dB in millibels
    val maxLevel: Int = 1500,  // +15dB in millibels
    val presets: List<EqualizerPresetEntity> = emptyList(),
    val selectedPresetId: Long = -1,
    val selectedPresetName: String = "Flat",
    val isCustomModified: Boolean = false
)

/**
 * ViewModel for the 10-band equalizer.
 * Manages band levels, loads/saves presets from Room, and provides default presets.
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val presetDao: EqualizerPresetDao,
    private val settingsDataStore: SettingsDataStore,
    private val eqEffect: com.powermediaplayer.audio.EqualizerEffectController,
    private val playbackConnection: com.powermediaplayer.service.PlaybackConnection,
    private val audioOutputDetector: com.powermediaplayer.audio.AudioOutputDetector
) : ViewModel() {

    /**
     * §C13 — last "manually selected" preset, used to restore when
     * headphones disconnect. Updated only by [selectPreset] (user
     * action), not by the auto-swap path so the auto-swap can revert.
     */
    @Volatile private var manuallySelectedPresetId: Long = -1L

    private val gson = Gson()
    private val _uiState = MutableStateFlow(EqualizerUiState())
    val uiState: StateFlow<EqualizerUiState> = _uiState.asStateFlow()

    /**
     * True when the active player is CastPlayer — EQ is attached to the
     * local ExoPlayer's audio session, so during cast the EQ has no
     * audible effect on the receiver. Drives a banner + grey-out
     * across the EQ tab.
     */
    val isCasting: StateFlow<Boolean> = playbackConnection.playerFlow
        .map { it is androidx.media3.cast.CastPlayer }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /**
     * Push current band levels to the live EQ. Interactive callers (user
     * selecting a preset / dragging a band / reset) mark the live value as a
     * deliberate USER write so a later VM construction won't clobber it; the
     * construction-time restore uses [EqualizerEffectController.restoreIfUntouched]
     * instead (see [restoreLastPreset]). (#9)
     */
    private fun pushLevels(
        levels: List<Int>,
        source: com.powermediaplayer.audio.EqualizerEffectController.EqSource =
            com.powermediaplayer.audio.EqualizerEffectController.EqSource.USER
    ) {
        eqEffect.setLive(levels, source)
    }

    init {
        // Seed default presets, restore last preset, then start
        // observing the table in a separate coroutine. The previous
        // ordering called `loadPresets()` which collects an infinite
        // Flow — `restoreLastPreset()` was unreachable.
        viewModelScope.launch {
            seedDefaultPresetsIfNeeded()
            restoreLastPreset()
        }
        viewModelScope.launch { loadPresets() }
        // §C13 — auto-swap on headphone plug-in / restore on unplug.
        // We deliberately observe the boolean transition (not the
        // current value alone) so a stale "true" at boot doesn't fire
        // a swap before the user has set anything up.
        viewModelScope.launch {
            audioOutputDetector.isHeadphonesConnected
                .combine(settingsDataStore.headphoneEqPresetId) { hp, id -> hp to id }
                .distinctUntilChanged()
                // #9 — the first combined value is the state AT construction, not
                // a plug/unplug transition. Dropping it stops a freshly built VM
                // (e.g. on expanding the Audio settings group) from auto-applying
                // a headphone preset over the live/override EQ.
                .drop(1)
                .collect { (connected, headphonePresetId) ->
                    if (headphonePresetId <= 0L) return@collect
                    if (connected) {
                        val preset = presetDao.getPresetById(headphonePresetId)
                        if (preset != null) {
                            applyPresetSilently(preset)
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "C13 headphone EQ swap: '${preset.name}' " +
                                    "(saved manual=$manuallySelectedPresetId)"
                            )
                        }
                    } else {
                        if (manuallySelectedPresetId > 0L) {
                            val preset = presetDao.getPresetById(manuallySelectedPresetId)
                            if (preset != null) {
                                applyPresetSilently(preset)
                                com.powermediaplayer.util.Diag.i(
                                    "PMP_DIAG",
                                    "C13 headphone EQ restore: '${preset.name}'"
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * §C13 — apply a preset to the live EQ + UI state WITHOUT touching
     * [manuallySelectedPresetId] or the persisted "last preset" key. Used
     * by the headphone-aware auto-swap path so the user's manual choice
     * is preserved across plug events.
     */
    private fun applyPresetSilently(preset: EqualizerPresetEntity) {
        val levels = parseBandLevels(preset.bandLevels)
        _uiState.value = _uiState.value.copy(
            bandLevels = levels,
            selectedPresetId = preset.id,
            selectedPresetName = preset.name,
            isCustomModified = false
        )
        pushLevels(levels)
    }

    fun setHeadphoneEqPreset(presetId: Long) {
        viewModelScope.launch { settingsDataStore.setHeadphoneEqPresetId(presetId) }
    }

    /** §C13 — exposed for the settings entry. */
    val headphoneEqPresetId: kotlinx.coroutines.flow.Flow<Long> =
        settingsDataStore.headphoneEqPresetId

    /** §C13 — per-paired-device EQ map (address → presetId). */
    val devicePresets: kotlinx.coroutines.flow.Flow<Map<String, Long>> =
        settingsDataStore.headphoneEqDevicePresets

    fun setDevicePreset(address: String, presetId: Long) {
        viewModelScope.launch {
            settingsDataStore.setHeadphoneEqDevicePreset(address, presetId)
        }
    }

    /**
     * Set a single band level.
     */
    fun setBandLevel(bandIndex: Int, level: Int) {
        val current = _uiState.value
        val newLevels = current.bandLevels.toMutableList()
        newLevels[bandIndex] = level.coerceIn(current.minLevel, current.maxLevel)
        _uiState.value = current.copy(
            bandLevels = newLevels,
            isCustomModified = true
        )
        pushLevels(newLevels)
    }

    /**
     * Select and apply a preset.
     */
    fun selectPreset(preset: EqualizerPresetEntity) {
        val levels = parseBandLevels(preset.bandLevels)
        _uiState.value = _uiState.value.copy(
            bandLevels = levels,
            selectedPresetId = preset.id,
            selectedPresetName = preset.name,
            isCustomModified = false
        )
        pushLevels(levels)
        // §C13 — manual selections are the "restore target" when
        // headphones disconnect; auto-swaps go through applyPresetSilently
        // which does NOT update this field.
        manuallySelectedPresetId = preset.id
        viewModelScope.launch {
            settingsDataStore.setLastEqPresetId(preset.id)
        }
    }

    /**
     * Save current band levels as a new user preset.
     */
    fun savePreset(name: String) {
        viewModelScope.launch {
            val levelsJson = gson.toJson(_uiState.value.bandLevels)
            val preset = EqualizerPresetEntity(
                name = name,
                isDefault = false,
                bandLevels = levelsJson
            )
            val id = presetDao.insertPreset(preset)
            _uiState.value = _uiState.value.copy(
                selectedPresetId = id,
                selectedPresetName = name,
                isCustomModified = false
            )
            settingsDataStore.setLastEqPresetId(id)
            loadPresets()
        }
    }

    /**
     * Delete a user-created preset.
     */
    fun deletePreset(presetId: Long) {
        viewModelScope.launch {
            presetDao.deleteUserPresetById(presetId)
            if (_uiState.value.selectedPresetId == presetId) {
                // Reset to Flat
                _uiState.value = _uiState.value.copy(
                    bandLevels = List(10) { 0 },
                    selectedPresetId = -1,
                    selectedPresetName = "Flat",
                    isCustomModified = false
                )
            }
            loadPresets()
        }
    }

    /**
     * Reset all bands to flat (0 dB).
     */
    fun resetToFlat() {
        val flat = List(10) { 0 }
        _uiState.value = _uiState.value.copy(
            bandLevels = flat,
            selectedPresetName = "Flat",
            isCustomModified = false
        )
        pushLevels(flat)
    }

    private suspend fun loadPresets() {
        presetDao.getAllPresets().collect { presets ->
            _uiState.value = _uiState.value.copy(presets = presets)
        }
    }

    private suspend fun restoreLastPreset() {
        val lastId = settingsDataStore.lastEqPresetId.first()
        if (lastId > 0) {
            val preset = presetDao.getPresetById(lastId) ?: return
            val levels = parseBandLevels(preset.bandLevels)
            // UI reflects the restored preset so the EQ screen shows it…
            _uiState.value = _uiState.value.copy(
                bandLevels = levels,
                selectedPresetId = preset.id,
                selectedPresetName = preset.name,
                isCustomModified = false
            )
            manuallySelectedPresetId = preset.id      // restore target only
            // …but the live engine is touched ONLY if nothing deliberate (a
            // per-track override / a value the user already set this session) is
            // live — this is the #9 fix for the audible shift on settings-expand.
            eqEffect.restoreIfUntouched(levels)
        }
    }

    private fun parseBandLevels(json: String): List<Int> {
        return try {
            val type = object : TypeToken<List<Int>>() {}.type
            gson.fromJson<List<Int>>(json, type)
        } catch (e: Exception) {
            List(10) { 0 }
        }
    }

    /**
     * Seed the database with default equalizer presets on first run.
     */
    private suspend fun seedDefaultPresetsIfNeeded() {
        if (presetDao.getPresetCount() > 0) return

        // Levels are millibels (100 mB = 1 dB). Designed to clear the
        // ~3–5 dB just-noticeable-difference threshold on phone
        // speakers / earbuds and give each preset a distinct character.
        val defaults = listOf(
            "Flat" to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            // Classical: bass warmth + smooth treble roll-off (was
            // treble-cut-only and too subtle to hear).
            "Classical" to listOf(300, 400, 200, 0, 0, 0, -400, -600, -800, -1000),
            "Rock" to listOf(500, 300, 0, -200, -300, 0, 300, 500, 700, 700),
            "Pop" to listOf(-200, 200, 500, 500, 300, 0, -200, -300, -200, -200),
            "Jazz" to listOf(0, 0, 300, 500, 300, 0, 300, 300, 500, 500),
            "Bass Boost" to listOf(800, 600, 400, 200, 0, 0, 0, 0, 0, 0),
            "Treble Boost" to listOf(0, 0, 0, 0, 0, 0, 200, 400, 600, 800),
            "Vocal" to listOf(-300, -200, 0, 400, 700, 700, 400, 0, -200, -300),
            "Electronic" to listOf(600, 400, 0, -200, -300, 0, 200, 400, 600, 600),
            // Acoustic: peaks raised ~+50% so the warmth/air shape is
            // audible on phone speakers (was peaks of 300–400 mB,
            // borderline JND).
            "Acoustic" to listOf(500, 400, 0, 300, 500, 300, 0, 300, 600, 500)
        )

        defaults.forEach { (name, levels) ->
            presetDao.insertPreset(
                EqualizerPresetEntity(
                    name = name,
                    isDefault = true,
                    bandLevels = gson.toJson(levels)
                )
            )
        }
    }
}
