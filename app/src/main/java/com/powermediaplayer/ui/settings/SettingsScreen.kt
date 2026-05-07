package com.powermediaplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.data.preferences.BluetoothMediaActions
import com.powermediaplayer.ui.theme.*

/**
 * Settings screen with toggles and layman explanations for:
 * - Metadata extraction mode (Standard / Deep Scan)
 * - Video decoding (Hardware / Software)
 * - Subtitle format preference (SRT / VTT / ASS/SSA)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TealAccent
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = OledBlack)
        )

        // ══════════════════════════════════════════════════════════
        // METADATA EXTRACTION
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Metadata Extraction")

        SettingsToggleItem(
            title = "Deep Scan",
            description = "When enabled, reads the entire file header to find missing tags " +
                    "in rare file types. This takes longer but finds more information like " +
                    "album art, track numbers, and genre in files that other players can't read.",
            icon = Icons.Filled.DocumentScanner,
            checked = uiState.useDeepScan,
            onCheckedChange = { viewModel.setDeepScan(it) }
        )

        SettingsDivider()

        // ══════════════════════════════════════════════════════════
        // VIDEO DECODING
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Video Decoding")

        SettingsToggleItem(
            title = "Software Decoding",
            description = "Hardware decoding (default) uses your phone's dedicated video chip " +
                    "for smooth, battery-efficient playback. Switch to Software decoding if you " +
                    "see visual corruption, green screens, or freezing — this uses the CPU instead, " +
                    "which is slower but more compatible.",
            icon = Icons.Filled.Memory,
            checked = uiState.useSoftwareDecoding,
            onCheckedChange = { viewModel.setSoftwareDecoding(it) }
        )

        // Status indicator
        Row(
            modifier = Modifier.padding(start = 72.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (uiState.useSoftwareDecoding) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (uiState.useSoftwareDecoding) WarningAmber else SuccessGreen,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.useSoftwareDecoding) "Using CPU decoding (slower, more compatible)"
                else "Using hardware decoding (faster, battery efficient)",
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.useSoftwareDecoding) WarningAmber else SuccessGreen
            )
        }

        SettingsDivider()

        // ══════════════════════════════════════════════════════════
        // SUBTITLE FORMAT
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Subtitle Format Preference")

        val formats = listOf(
            SubtitleFormatInfo(
                code = "SRT",
                name = "SRT — SubRip Text",
                description = "Simple text subtitles. Just words on screen with basic timing. " +
                        "Works everywhere — the most universally supported format."
            ),
            SubtitleFormatInfo(
                code = "VTT",
                name = "VTT — Web Video Text Tracks",
                description = "Web-standard subtitles. Supports basic styling like bold and colors. " +
                        "Used by most streaming services including YouTube and Netflix."
            ),
            SubtitleFormatInfo(
                code = "ASS",
                name = "ASS/SSA — Advanced SubStation Alpha",
                description = "Advanced subtitles with full typographic control — custom fonts, " +
                        "positioned text, karaoke effects, and animated styling. " +
                        "Common in anime fansubs and professional subtitle work."
            )
        )

        formats.forEach { format ->
            SubtitleFormatOption(
                format = format,
                isSelected = uiState.subtitleFormat == format.code,
                onSelect = { viewModel.setSubtitleFormat(format.code) }
            )
        }

        SettingsDivider()

        // ══════════════════════════════════════════════════════════
        // DISPLAY — cover-art scaling mode for the now-playing screen
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Display")
        Text(
            text = "Choose whether the now-playing cover art shows the " +
                "whole image with margins (Fit) or fills the screen, " +
                "cropping edges if needed (Fill).",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        ArtworkScalePicker(
            currentMode = uiState.artworkScaleMode,
            onModeChange = { viewModel.setArtworkScaleMode(it) }
        )
        SettingsDivider()

        // ══════════════════════════════════════════════════════════
        // AUTO-HIDE CONTROLS (Phase 2 — §D2)
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Auto-hide controls")
        Text(
            text = "How long the on-screen controls stay visible after you " +
                "stop touching the screen. 'Never' keeps them until you tap.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        AutoHideRow(
            label = "Video controls",
            description = "Player buttons + slider while watching video.",
            currentSeconds = uiState.videoControlsHideSec,
            onChange = { viewModel.setVideoControlsHideSec(it) },
            allowNever = true
        )
        AutoHideRow(
            label = "Audio effects popup",
            description = "Reverb / stereo flip / mono mix sub-popup.",
            currentSeconds = uiState.audioEffectsPopupHideSec,
            onChange = { viewModel.setAudioEffectsPopupHideSec(it) },
            allowNever = true
        )
        AutoHideRow(
            label = "Video effects popup",
            description = "Mirror / B&W / sepia / rotation sub-popup.",
            currentSeconds = uiState.videoEffectsPopupHideSec,
            onChange = { viewModel.setVideoEffectsPopupHideSec(it) },
            allowNever = true
        )
        SettingsDivider()

        // ══════════════════════════════════════════════════════════
        // BLUETOOTH — car media-button remapping
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Bluetooth Car Controls")
        Text(
            text = "Remap the Previous and Next buttons on your car stereo " +
                "(or any Bluetooth remote) when playing through this app. " +
                "Works with any car that already controls media over Bluetooth — " +
                "no setup needed in the car.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )

        BluetoothActionPicker(
            label = "Previous button does",
            currentAction = uiState.btPrevAction,
            seconds = uiState.btSkipBackSeconds,
            options = PREV_OPTIONS,
            onActionChange = { viewModel.setBtPrevAction(it) },
            onSecondsChange = { viewModel.setBtSkipBackSeconds(it) }
        )

        BluetoothActionPicker(
            label = "Next button does",
            currentAction = uiState.btNextAction,
            seconds = uiState.btSkipForwardSeconds,
            options = NEXT_OPTIONS,
            onActionChange = { viewModel.setBtNextAction(it) },
            onSecondsChange = { viewModel.setBtSkipForwardSeconds(it) }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ══════════════════════════════════════════════════════════
        // POWER-USER FEATURES — Video effects (Mirror H/V, B&W,
        // Rotation) live in the player overlay only when a video is
        // loaded; see PlayerScreen.VideoEffectsButton.
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Audio extras")
        SettingsToggleItem("Reverse audio (local files)",
            "Play local files backwards. Cloud streams unsupported.",
            Icons.Filled.SwapHoriz, uiState.audioReverseLocal) { viewModel.setAudioReverseLocal(it) }
        SliderRow("Independent pitch", "${"%.2f".format(uiState.pitch)}×",
            uiState.pitch, 0.5f..2.0f) { viewModel.setPitch(it) }
        SliderRow("Volume boost", "+${uiState.volumeBoostMb / 100} dB",
            uiState.volumeBoostMb.toFloat(), 0f..2000f) { viewModel.setVolumeBoost(it.toInt()) }
        SliderRow("Subtitle delay", "${uiState.subtitleDelayMs} ms",
            uiState.subtitleDelayMs.toFloat(), -5000f..5000f) { viewModel.setSubtitleDelay(it.toInt()) }
        SliderRow("Audio delay", "${uiState.audioDelayMs} ms",
            uiState.audioDelayMs.toFloat(), -2000f..2000f) { viewModel.setAudioDelay(it.toInt()) }
        SliderRow("Crossfade", "${uiState.crossfadeMs} ms",
            uiState.crossfadeMs.toFloat(), 0f..10_000f) { viewModel.setCrossfade(it.toInt()) }
        SettingsToggleItem("Gapless playback", "Seamless transitions between tracks.",
            Icons.Filled.SkipNext, uiState.gaplessPlayback) { viewModel.setGapless(it) }
        SettingsToggleItem("ReplayGain normalisation",
            "Even out loudness across tracks using their REPLAYGAIN tags.",
            Icons.Filled.GraphicEq, uiState.replayGainEnabled) { viewModel.setReplayGain(it) }
        SettingsToggleItem("Resume on Bluetooth connect",
            "Auto-resume the last track when a BT audio device reconnects.",
            Icons.Filled.Bluetooth, uiState.resumeOnBt) { viewModel.setResumeOnBt(it) }
        SettingsToggleItem("Pre-fetch next cloud track",
            "Buffer the next item in a cloud queue for seamless transition.",
            Icons.Filled.CloudDownload, uiState.prefetchNextCloud) { viewModel.setPrefetchNextCloud(it) }

        SettingsDivider()

        // ══════════════════════════════════════════════════════════
        // AUDIO EFFECTS
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Audio effects")

        // Reverb preset chooser (radio-style row).
        Text(
            "Reverb",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp)
        )
        val reverbOptions = listOf(
            0 to "Off",
            1 to "Room",
            2 to "Medium hall",
            3 to "Large hall",
            4 to "Plate",
            5 to "Cave"
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            reverbOptions.forEach { (id, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setReverbPreset(id) }
                        .padding(vertical = 6.dp)
                ) {
                    RadioButton(
                        selected = uiState.reverbPreset == id,
                        onClick = { viewModel.setReverbPreset(id) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = TextPrimary)
                }
            }
        }
        SettingsToggleItem(
            "Stereo flip (L↔R)",
            "Swap left and right channels. Useful when headphones " +
                "are mis-wired or for spatial-audio experiments.",
            Icons.Filled.SwapHoriz,
            uiState.stereoFlip
        ) { viewModel.setStereoFlip(it) }
        SettingsToggleItem(
            "Mono mix",
            "Mix both channels into a centred mono image (still " +
                "output as stereo so headphones receive the same on " +
                "both ears).",
            Icons.Filled.Adjust,
            uiState.monoMix
        ) { viewModel.setMonoMix(it) }
        SettingsToggleItem(
            "Multi-channel passthrough",
            "When on, 5.1/7.1/Dolby/DTS audio bitstream is sent to a " +
                "connected receiver / HDMI sink unchanged so it can " +
                "decode itself. Off forces software downmix to stereo.",
            Icons.Filled.Speaker,
            uiState.passthroughAudio
        ) { viewModel.setPassthroughAudio(it) }

        SettingsDivider()

        // ══════════════════════════════════════════════════════════
        // ABOUT
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("About")

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = "Power Media Player",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent
            )
            Text(
                text = "Version 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Built with Media3 ExoPlayer, FFmpeg, Jetpack Compose",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ── Reusable Setting Components ──────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = TealAccent,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsToggleItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) TealAccent else DisabledGrey,
            modifier = Modifier
                .size(24.dp)
                .padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TealAccent,
                checkedTrackColor = Teal800,
                uncheckedThumbColor = DisabledGrey,
                uncheckedTrackColor = SurfaceElevated
            )
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    // Drag-local state so the dragging visual is fluid; commit to
    // DataStore + downstream reactor (PlayerViewModel.init flows for
    // pitch / volume-boost / replay-gain) only on release. The
    // previous design fired DataStore.edit() per pixel of drag,
    // chaining dozens of redundant writes.
    var local by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = TextPrimary,
                modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = TealAccent)
        }
        Slider(
            value = local,
            valueRange = range,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            colors = SliderDefaults.colors(
                thumbColor = TealAccent,
                activeTrackColor = TealAccent,
                inactiveTrackColor = TealAccent.copy(alpha = 0.25f)
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        color = SurfaceElevated
    )
}

data class SubtitleFormatInfo(
    val code: String,
    val name: String,
    val description: String
)

// ── Bluetooth car-button mapping options ─────────────────────────────
private data class BtActionOption(
    val token: String,
    val label: String,
    val needsSeconds: Boolean
)

private val PREV_OPTIONS = listOf(
    BtActionOption(BluetoothMediaActions.PREV_TRACK,    "Previous track / chapter", false),
    BtActionOption(BluetoothMediaActions.SKIP_BACK,     "Skip backward …",          true),
    BtActionOption(BluetoothMediaActions.RESTART_TRACK, "Restart current track",    false),
    BtActionOption(BluetoothMediaActions.PREV_CHAPTER,  "Previous chapter only",    false)
)

private val NEXT_OPTIONS = listOf(
    BtActionOption(BluetoothMediaActions.NEXT_TRACK,    "Next track / chapter", false),
    BtActionOption(BluetoothMediaActions.SKIP_FORWARD,  "Skip forward …",       true),
    BtActionOption(BluetoothMediaActions.NEXT_CHAPTER,  "Next chapter only",    false)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BluetoothActionPicker(
    label: String,
    currentAction: String,
    seconds: Int,
    options: List<BtActionOption>,
    onActionChange: (String) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    val selected = options.firstOrNull { it.token == currentAction } ?: options.first()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt.label,
                                color = if (opt.token == currentAction) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onActionChange(opt.token)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        if (selected.needsSeconds) {
            Spacer(Modifier.height(8.dp))
            SecondsStepper(
                seconds = seconds,
                onChange = onSecondsChange
            )
        }
    }
}

@Composable
private fun SecondsStepper(seconds: Int, onChange: (Int) -> Unit) {
    val presets = listOf(5, 10, 15, 30, 60, 90)
    Column(modifier = Modifier.fillMaxWidth()) {
        // Primary stepper row — fixed (no scroll) so the +/- buttons
        // are always reachable on the narrowest folded-phone width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$seconds s",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            IconButton(onClick = { onChange((seconds - 5).coerceAtLeast(1)) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease seconds", tint = TealAccent)
            }
            IconButton(onClick = { onChange(seconds + 5) }) {
                Icon(Icons.Filled.Add, contentDescription = "Increase seconds", tint = TealAccent)
            }
        }
        Spacer(Modifier.height(4.dp))
        // Preset chips — horizontal-scrollable so 6 chips never clip on
        // a folded phone or split-screen window.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presets.forEach { p ->
                AssistChip(
                    onClick = { onChange(p) },
                    label = { Text("${p}s", style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (p == seconds) Teal800 else SurfaceElevated,
                        labelColor = if (p == seconds) TealAccent else TextSecondary
                    )
                )
            }
        }
    }
}

@Composable
private fun SubtitleFormatOption(
    format: SubtitleFormatInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = TealAccent,
                unselectedColor = DisabledGrey
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = format.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isSelected) TealAccent else TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = format.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

/**
 * One-row dropdown for an auto-hide timer: "Never" + seconds.
 * Stores 0 = Never; positive int = seconds. Default range is the
 * superset of the spec — Never / 1 / 2 / 3 / 4 / 6 / 8 — so all three
 * call sites share the same option list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoHideRow(
    label: String,
    description: String,
    currentSeconds: Int,
    onChange: (Int) -> Unit,
    allowNever: Boolean
) {
    val options: List<Pair<Int, String>> = buildList {
        if (allowNever) add(0 to "Never")
        addAll(listOf(1 to "1 s", 2 to "2 s", 3 to "3 s", 4 to "4 s", 6 to "6 s", 8 to "8 s"))
    }
    val selected = options.firstOrNull { it.first == currentSeconds } ?: options.last()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        Spacer(Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.second,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                options.forEach { (sec, lbl) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = lbl,
                                color = if (sec == currentSeconds) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onChange(sec)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtworkScalePicker(
    currentMode: String,
    onModeChange: (String) -> Unit
) {
    val options = listOf(
        "fit" to "Fit (show whole cover)",
        "fill" to "Fill (no margins, may crop edges)"
    )
    val selected = options.firstOrNull { it.first == currentMode } ?: options.first()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Cover-art sizing",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.second,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                options.forEach { (token, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (token == currentMode) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onModeChange(token)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
    }
}
