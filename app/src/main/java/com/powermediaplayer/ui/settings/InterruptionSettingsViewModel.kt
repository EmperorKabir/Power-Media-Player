package com.powermediaplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Small dedicated VM for the two audio-interruption toggles (resume-after-
 * interruption + interruptions-pause-cast). Kept separate so the large
 * SettingsViewModel positional `combine` doesn't have to be re-indexed.
 */
@HiltViewModel
class InterruptionSettingsViewModel @Inject constructor(
    private val settings: SettingsDataStore
) : ViewModel() {

    val resumeAfter: StateFlow<Boolean> = settings.audioResumeAfterInterruption
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val pauseCast: StateFlow<Boolean> = settings.interruptionsPauseCast
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setResumeAfter(enabled: Boolean) {
        viewModelScope.launch { settings.setAudioResumeAfterInterruption(enabled) }
    }

    fun setPauseCast(enabled: Boolean) {
        viewModelScope.launch { settings.setInterruptionsPauseCast(enabled) }
    }
}
