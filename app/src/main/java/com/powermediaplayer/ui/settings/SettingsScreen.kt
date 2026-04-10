package com.powermediaplayer.ui.settings

import androidx.compose.foundation.background
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

        Spacer(modifier = Modifier.height(32.dp))

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
