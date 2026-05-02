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
    val btSkipForwardSeconds: Int = 30
)

/**
 * ViewModel for the Settings screen.
 * Reads and writes user preferences via SettingsDataStore.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        listOf(
            settingsDataStore.useDeepScan,
            settingsDataStore.subtitleFormat,
            settingsDataStore.useSoftwareDecoding,
            settingsDataStore.btPrevAction,
            settingsDataStore.btNextAction,
            settingsDataStore.btSkipBackSeconds,
            settingsDataStore.btSkipForwardSeconds
        )
    ) { values ->
        SettingsUiState(
            useDeepScan = values[0] as Boolean,
            subtitleFormat = values[1] as String,
            useSoftwareDecoding = values[2] as Boolean,
            btPrevAction = values[3] as String,
            btNextAction = values[4] as String,
            btSkipBackSeconds = values[5] as Int,
            btSkipForwardSeconds = values[6] as Int
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setDeepScan(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDeepScan(enabled) }
    }

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
}
