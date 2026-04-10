package com.powermediaplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val useSoftwareDecoding: Boolean = false
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
        settingsDataStore.useDeepScan,
        settingsDataStore.subtitleFormat,
        settingsDataStore.useSoftwareDecoding
    ) { deepScan, subtitleFormat, softwareDecoding ->
        SettingsUiState(
            useDeepScan = deepScan,
            subtitleFormat = subtitleFormat,
            useSoftwareDecoding = softwareDecoding
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setDeepScan(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setDeepScan(enabled)
        }
    }

    fun setSubtitleFormat(format: String) {
        viewModelScope.launch {
            settingsDataStore.setSubtitleFormat(format)
        }
    }

    fun setSoftwareDecoding(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSoftwareDecoding(enabled)
        }
    }
}
