package com.powermediaplayer.ui.overrides

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.powermediaplayer.data.db.dao.MediaOverrideDao
import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §C7 — per-file playback override popup. Three sub-tabs: Audio /
 * Video / Speed. Each axis is a switch + value control; flipping
 * the switch off marks the column NULL → falls through to the global
 * setting at play time.
 *
 * Hosted from the long-press [TrackContextSheet] when the row is
 * starred or pinned. Caller passes the live [MediaOverrideDao] +
 * the row's `mediaUri`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaOverridesPopup(
    mediaUri: String,
    title: String,
    dao: MediaOverrideDao,
    onDismiss: () -> Unit
) {
    val current by dao.getByUri(mediaUri).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    // Local edit buffer keyed off the latest persisted row. Each axis
    // is "active or null" — flipping the switch off persists null, on
    // persists the slider value.
    var draft by remember(current) {
        mutableStateOf(current ?: MediaOverrideEntity(mediaUri = mediaUri))
    }

    fun save(updated: MediaOverrideEntity) {
        draft = updated
        scope.launch(Dispatchers.IO) {
            if (updated.isEmpty()) dao.clear(mediaUri)
            else dao.upsert(updated.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black
    ) {
        Column(modifier = Modifier
            .widthIn(max = 560.dp)   // audit 8.1 (F5) — no full-width tablet sheet
            .align(Alignment.CenterHorizontally)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Custom settings for this file",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Each switch makes that setting fall back to the " +
                    "global default. Saved values apply automatically the " +
                    "next time this file plays.",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = tab, containerColor = Color.Black) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Audio") })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("Video") })
                Tab(selected = tab == 2, onClick = { tab = 2 },
                    text = { Text("Speed") })
            }
            Spacer(Modifier.height(8.dp))

            when (tab) {
                0 -> AudioTab(draft) { save(it) }
                1 -> VideoTab(draft) { save(it) }
                2 -> SpeedTab(draft) { save(it) }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = {
                    save(MediaOverrideEntity(mediaUri = mediaUri))
                }) { Text("Clear all overrides", color = ErrorRed) }
                // vc31 consistency: was the only filled Button among the
                // app's dialog actions; Material3 dialogs use TextButton.
                TextButton(onClick = onDismiss) { Text("Done", color = TealAccent) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AudioTab(
    draft: MediaOverrideEntity,
    onSave: (MediaOverrideEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AxisSwitch(
            label = "Stereo flip",
            active = draft.stereoFlip != null,
            value = draft.stereoFlip == true,
            onActiveChange = {
                onSave(draft.copy(stereoFlip = if (it) false else null))
            },
            onValueChange = { onSave(draft.copy(stereoFlip = it)) }
        )
        AxisSwitch(
            label = "Mono mix",
            active = draft.monoMix != null,
            value = draft.monoMix == true,
            onActiveChange = {
                onSave(draft.copy(monoMix = if (it) false else null))
            },
            onValueChange = { onSave(draft.copy(monoMix = it)) }
        )
        AxisChips(
            label = "Reverb preset",
            active = draft.reverbPreset != null,
            currentIndex = draft.reverbPreset ?: 0,
            options = listOf(
                0 to "Off", 1 to "Room", 2 to "Medium hall",
                3 to "Large hall", 4 to "Plate", 5 to "Cave"
            ),
            onActiveChange = {
                onSave(draft.copy(reverbPreset = if (it) 0 else null))
            },
            onPick = { onSave(draft.copy(reverbPreset = it)) }
        )
        AxisSlider(
            label = "Volume boost (mB)",
            active = draft.volumeBoostMb != null,
            value = (draft.volumeBoostMb ?: 0).toFloat(),
            range = 0f..2000f, steps = 19,
            onActiveChange = {
                onSave(draft.copy(volumeBoostMb = if (it) 0 else null))
            },
            onValueChange = {
                onSave(draft.copy(volumeBoostMb = it.toInt().coerceIn(0, 2000)))
            },
            display = { v -> "${v.toInt()} mB" }
        )
    }
}

@Composable
private fun VideoTab(
    draft: MediaOverrideEntity,
    onSave: (MediaOverrideEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AxisSwitch(
            label = "Mirror horizontal",
            active = draft.videoFlipH != null,
            value = draft.videoFlipH == true,
            onActiveChange = {
                onSave(draft.copy(videoFlipH = if (it) false else null))
            },
            onValueChange = { onSave(draft.copy(videoFlipH = it)) }
        )
        AxisSwitch(
            label = "Mirror vertical",
            active = draft.videoFlipV != null,
            value = draft.videoFlipV == true,
            onActiveChange = {
                onSave(draft.copy(videoFlipV = if (it) false else null))
            },
            onValueChange = { onSave(draft.copy(videoFlipV = it)) }
        )
        AxisSwitch(
            label = "Black & white",
            active = draft.videoBw != null,
            value = draft.videoBw == true,
            onActiveChange = {
                onSave(draft.copy(videoBw = if (it) false else null))
            },
            onValueChange = { onSave(draft.copy(videoBw = it)) }
        )
        AxisSwitch(
            label = "Sepia",
            active = draft.videoSepia != null,
            value = draft.videoSepia == true,
            onActiveChange = {
                onSave(draft.copy(videoSepia = if (it) false else null))
            },
            onValueChange = { onSave(draft.copy(videoSepia = it)) }
        )
        AxisSwitch(
            label = "Invert",
            active = draft.videoInvert != null,
            value = draft.videoInvert == true,
            onActiveChange = {
                onSave(draft.copy(videoInvert = if (it) false else null))
            },
            onValueChange = { onSave(draft.copy(videoInvert = it)) }
        )
        AxisSlider(
            label = "Rotation (degrees)",
            active = draft.videoRotation != null,
            value = (draft.videoRotation ?: 0).toFloat(),
            range = 0f..270f, steps = 2,
            onActiveChange = {
                onSave(draft.copy(videoRotation = if (it) 0 else null))
            },
            onValueChange = {
                val snapped = (it / 90f).toInt() * 90
                onSave(draft.copy(videoRotation = snapped))
            },
            display = { v -> "${v.toInt()}°" }
        )
    }
}

@Composable
private fun SpeedTab(
    draft: MediaOverrideEntity,
    onSave: (MediaOverrideEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AxisSlider(
            label = "Playback speed",
            active = draft.playbackSpeed != null,
            value = draft.playbackSpeed ?: 1.0f,
            range = 0.5f..2.0f, steps = 14,
            onActiveChange = {
                onSave(draft.copy(playbackSpeed = if (it) 1.0f else null))
            },
            onValueChange = {
                onSave(draft.copy(playbackSpeed = it.coerceIn(0.5f, 2.0f)))
            },
            display = { v -> "%.2fx".format(v) }
        )
        AxisSlider(
            label = "Pitch",
            active = draft.pitch != null,
            value = draft.pitch ?: 1.0f,
            range = 0.5f..2.0f, steps = 14,
            onActiveChange = {
                onSave(draft.copy(pitch = if (it) 1.0f else null))
            },
            onValueChange = {
                onSave(draft.copy(pitch = it.coerceIn(0.5f, 2.0f)))
            },
            display = { v -> "%.2fx".format(v) }
        )
    }
}

@Composable
private fun AxisSwitch(
    label: String,
    active: Boolean,
    value: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onValueChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = active, onCheckedChange = onActiveChange)
        Text(
            label,
            color = if (active) TextPrimary else TextTertiary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        )
        if (active) {
            Switch(checked = value, onCheckedChange = onValueChange)
        }
    }
}

@Composable
private fun AxisSlider(
    label: String,
    active: Boolean,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onActiveChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    display: (Float) -> String
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = active, onCheckedChange = onActiveChange)
            Text(
                label,
                color = if (active) TextPrimary else TextTertiary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Text(
                if (active) display(value) else "—",
                color = TextSecondary
            )
        }
        if (active) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = steps
            )
        }
    }
}

/**
 * Axis with a fixed enum-style option set rendered as a horizontal
 * scrollable chip row. Used by the per-file reverb override where a
 * slider made the steps invisible until the user already selected one.
 */
@Composable
private fun AxisChips(
    label: String,
    active: Boolean,
    currentIndex: Int,
    options: List<Pair<Int, String>>,
    onActiveChange: (Boolean) -> Unit,
    onPick: (Int) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = active, onCheckedChange = onActiveChange)
            Text(
                label,
                color = if (active) TextPrimary else TextTertiary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
        }
        if (active) {
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { (idx, name) ->
                    androidx.compose.material3.FilterChip(
                        selected = idx == currentIndex,
                        onClick = { onPick(idx) },
                        label = { Text(name) }
                    )
                }
            }
        }
    }
}

private fun reverbLabel(i: Int): String = when (i) {
    0 -> "Off"
    1 -> "Room"
    2 -> "Medium hall"
    3 -> "Large hall"
    4 -> "Plate"
    5 -> "Cave"
    else -> i.toString()
}
