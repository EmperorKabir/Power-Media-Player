package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.BrightnessHelper

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.3f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/**
 * Volume slider (primary) + Brightness slider (secondary).
 * Volume comes first as it is the most commonly adjusted control.
 * Brightness is secondary — dimming the screen is a less frequent action.
 */
@Composable
fun VolumeAndBrightnessControls(
    currentVolume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
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
        // ── 1. Volume Slider (first — most used) ─────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = "Volume down",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Slider(
                value = currentVolume.toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..maxVolume.toFloat(),
                steps = (maxVolume - 1).coerceAtLeast(0),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = TealAccent,
                    activeTrackColor = TealAccent,
                    inactiveTrackColor = TealAccent.copy(alpha = 0.25f)
                )
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Volume up",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── 2. Brightness Slider (second — less frequent) ────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BrightnessLow,
                contentDescription = "Brightness low",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Slider(
                value = brightness,
                // While dragging only update local state — committing to
                // Settings.System for every delta event spammed
                // MdnieScenarioControlService and caused visible screen
                // jumps. The actual write happens on release via
                // onValueChangeFinished.
                onValueChange = { value -> brightness = value },
                onValueChangeFinished = {
                    if (BrightnessHelper.canWriteSettings(context)) {
                        BrightnessHelper.setBrightnessFloat(context, brightness)
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
                    inactiveTrackColor = TealAccent.copy(alpha = 0.25f),
                    disabledThumbColor = DisabledGrey,
                    disabledActiveTrackColor = DisabledGrey,
                    disabledInactiveTrackColor = DisabledContent
                )
            )
            Icon(
                imageVector = Icons.Filled.BrightnessHigh,
                contentDescription = "Brightness high",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        // WRITE_SETTINGS permission hint — re-checks on every
        // ON_RESUME so the banner disappears as soon as the user grants
        // permission in Settings (and reappears if the user revokes it
        // later). Without the lifecycle observer the canWriteSettings
        // check ran at composition time only and the banner stuck
        // around even after the grant.
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        var canWrite by remember {
            mutableStateOf(BrightnessHelper.canWriteSettings(context))
        }
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    canWrite = BrightnessHelper.canWriteSettings(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        if (!canWrite) {
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
 * Playback speed dropdown — Material 3 ExposedDropdownMenuBox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedControl(
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var speedMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Speed",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )

        ExposedDropdownMenuBox(
            expanded = speedMenuExpanded,
            onExpandedChange = { speedMenuExpanded = it },
            // widthIn(min=…) lets the dropdown grow with the user's
            // font scale — a hard width(110.dp) clips "1.75×" / "2.5×"
            // at scale ≥ 1.4×.
            modifier = Modifier.widthIn(min = 110.dp)
        ) {
            OutlinedTextField(
                value = formatSpeed(playbackSpeed),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speedMenuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .height(48.dp),
                textStyle = MaterialTheme.typography.labelLarge,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated,
                    focusedTrailingIconColor = TealAccent,
                    unfocusedTrailingIconColor = TextSecondary
                ),
                shape = RoundedCornerShape(10.dp)
            )

            ExposedDropdownMenu(
                expanded = speedMenuExpanded,
                onDismissRequest = { speedMenuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                SPEED_OPTIONS.forEach { speed ->
                    val isSelected = kotlin.math.abs(playbackSpeed - speed) < 0.01f
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = formatSpeed(speed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onSpeedChange(speed)
                            speedMenuExpanded = false
                        },
                        trailingIcon = if (isSelected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = TealAccent, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.background(
                            if (isSelected) TealAccent.copy(alpha = 0.1f) else SurfaceElevated
                        )
                    )
                }
            }
        }
    }
}

/**
 * A prepared, modular speed control component.
 * Places the 'Speed' text nearer to the setting alongside a running guy icon.
 * Intended to be placed by the coordinator agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreparedSpeedComponent(
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var speedMenuExpanded by remember { mutableStateOf(false) }
    val tint = if (enabled) TextSecondary else DisabledGrey

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.DirectionsRun,
            contentDescription = "Speed Icon",
            // Greyed when speed control is disabled (e.g. Spotify mirror —
            // Spotify Connect Web API has no playback-speed endpoint).
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "Speed",
            style = MaterialTheme.typography.labelMedium,
            color = tint
        )

        ExposedDropdownMenuBox(
            expanded = enabled && speedMenuExpanded,
            onExpandedChange = { if (enabled) speedMenuExpanded = it },
            modifier = Modifier.width(110.dp)
        ) {
            OutlinedTextField(
                value = formatSpeed(playbackSpeed),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = enabled && speedMenuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                    .height(48.dp),
                textStyle = MaterialTheme.typography.labelLarge,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated,
                    focusedTrailingIconColor = TealAccent,
                    unfocusedTrailingIconColor = TextSecondary,
                    disabledBorderColor = DisabledGrey,
                    disabledTextColor = DisabledGrey,
                    disabledTrailingIconColor = DisabledGrey,
                    disabledContainerColor = SurfaceElevated.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(10.dp)
            )

            ExposedDropdownMenu(
                expanded = enabled && speedMenuExpanded,
                onDismissRequest = { speedMenuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                SPEED_OPTIONS.forEach { speed ->
                    val isSelected = kotlin.math.abs(playbackSpeed - speed) < 0.01f
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = formatSpeed(speed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onSpeedChange(speed)
                            speedMenuExpanded = false
                        },
                        trailingIcon = if (isSelected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = TealAccent, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.background(
                            if (isSelected) TealAccent.copy(alpha = 0.1f) else SurfaceElevated
                        )
                    )
                }
            }
        }
        // Reset speed → 1×. Greyed alongside the dropdown when speed
        // control is disabled (e.g. Spotify mirror).
        TextButton(
            onClick = { if (enabled) onSpeedChange(1.0f) },
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = TealAccent,
                disabledContentColor = DisabledGrey
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Reset Speed",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * Sleep timer button — shows active countdown if timer is running.
 */
@Composable
fun SleepTimerButton(
    sleepTimerActive: Boolean,
    sleepTimerFormatted: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        FilledTonalButton(
            onClick = onClick,
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

private fun formatSpeed(speed: Float): String {
    return if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "${speed}x"
}

// ── Legacy alias kept for backward compatibility ──────────────────────
// (SecondaryControls was the old combined composable; split into
//  SpeedControl + VolumeAndBrightnessControls + SleepTimerButton)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryControls(
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    brightnessEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    SpeedControl(playbackSpeed = playbackSpeed, onSpeedChange = onSpeedChange, modifier = modifier)
}

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
    // Kept for legacy call sites in PlayerScreen — wraps the new components
    VolumeAndBrightnessControls(
        currentVolume = currentVolume,
        maxVolume = maxVolume,
        onVolumeChange = onVolumeChange,
        brightnessEnabled = true,
        modifier = modifier
    )
    Spacer(modifier = Modifier.height(4.dp))
    SleepTimerButton(
        sleepTimerActive = sleepTimerActive,
        sleepTimerFormatted = sleepTimerFormatted,
        onClick = onSleepTimerClick
    )
}
