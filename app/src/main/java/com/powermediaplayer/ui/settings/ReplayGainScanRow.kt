package com.powermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.replaygain.ReplayGainScanner
import com.powermediaplayer.ui.library.LibraryViewModel
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * §C18 — "Scan now" UI for the ReplayGain pre-scanner. Pulls the
 * full local audio library from [LibraryViewModel.uiState] and feeds
 * it to [ReplayGainScanner.scan] off the main thread. Shows live
 * progress + last-scanned count.
 */
@HiltViewModel
class ReplayGainScanViewModel @Inject constructor(
    private val scanner: ReplayGainScanner
) : ViewModel() {
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastCount = MutableStateFlow(0)
    val lastCount: StateFlow<Int> = _lastCount.asStateFlow()

    fun scan(libraryVm: LibraryViewModel) {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val files = libraryVm.uiState.value.audioFiles
            val n = scanner.scan(files)
            _lastCount.value = n
            _scanning.value = false
        }
    }
}

@Composable
fun ReplayGainScanRow(
    libraryVm: LibraryViewModel = hiltViewModel(),
    vm: ReplayGainScanViewModel = hiltViewModel()
) {
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val count by vm.lastCount.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.GraphicEq, contentDescription = null,
            tint = TealAccent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Scan ReplayGain now",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                if (scanning) "Scanning…"
                else if (count > 0) "Last scan: $count file(s)"
                else "Measures every audio file once and remembers its loudness. " +
                    "Used while playing when 'Even out track volumes' is on.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
        Button(
            onClick = { vm.scan(libraryVm) },
            enabled = !scanning
        ) {
            Text(if (scanning) "…" else "Scan", color = TextPrimary)
        }
    }
}
