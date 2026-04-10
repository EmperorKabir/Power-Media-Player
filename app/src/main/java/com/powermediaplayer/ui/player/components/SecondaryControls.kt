package com.powermediaplayer.ui.player.components

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.BrightnessHelper
import com.powermediaplayer.util.TimeFormatter

/**
 * Secondary controls row:
 * - Playback speed selector (0.5x - 3.0x)
 * - Screen brightness slider
 */
@Composable
fun SecondaryControls(
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    brightnessEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var brightness by remember { mutableFloatStateOf(BrightnessHelper.getBrightnessFloat(context)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // ── Playback Speed ───────────────────────────────────────
        Text(
            text = "Speed",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            speeds.forEach { speed ->
                val isSelected = kotlin.math.abs(playbackSpeed - speed) < 0.01f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) TealAccent else SurfaceElevated)
                        .clickable { onSpeedChange(speed) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${speed}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) OledBlack else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Brightness Slider ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BrightnessLow,
                contentDescription = "Brightness",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )

            Slider(
                value = brightness,
                onValueChange = { value ->
                    brightness = value
                    if (BrightnessHelper.canWriteSettings(context)) {
                        BrightnessHelper.setBrightnessFloat(context, value)
                    }
                },
                enabled = brightnessEnabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Teal300,
                    activeTrackColor = Teal300,
                    inactiveTrackColor = DisabledContent,
                    disabledThumbColor = DisabledGrey,
                    disabledActiveTrackColor = DisabledGrey,
                    disabledInactiveTrackColor = DisabledContent
                )
            )

            Icon(
                imageVector = Icons.Filled.BrightnessHigh,
                contentDescription = "Max brightness",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Permission hint if WRITE_SETTINGS not granted
        if (!BrightnessHelper.canWriteSettings(context)) {
            Text(
                text = "Tap to grant brightness permission",
                style = MaterialTheme.typography.bodySmall,
                color = TealAccent,
                modifier = Modifier
                    .padding(start = 28.dp, top = 2.dp)
                    .clickable { BrightnessHelper.requestWriteSettingsPermission(context) }
            )
        }
    }
}

/**
 * Volume and sleep timer controls row.
 */
@Composable
fun TertiaryControls(
    currentVolume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    sleepTimerActive: Boolean,
    sleepTimerFormatted: String,
    onSleepTimerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // ── Volume Slider (AudioManager) ─────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.VolumeDown,
                contentDescription = "Volume down",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )

            Slider(
                value = currentVolume.toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..maxVolume.toFloat(),
                steps = maxVolume - 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = TealAccent,
                    activeTrackColor = TealAccent,
                    inactiveTrackColor = DisabledContent
                )
            )

            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = "Volume up",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Sleep Timer Button ───────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onSleepTimerClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (sleepTimerActive) Teal800 else SurfaceElevated,
                    contentColor = if (sleepTimerActive) TealAccent else TextSecondary
                ),
                modifier = Modifier.height(40.dp)
            ) {
                Icon(
                    imageVector = if (sleepTimerActive) Icons.Filled.TimerOff else Icons.Filled.Timer,
                    contentDescription = "Sleep timer",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (sleepTimerActive) "Sleep: $sleepTimerFormatted" else "Sleep Timer",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
