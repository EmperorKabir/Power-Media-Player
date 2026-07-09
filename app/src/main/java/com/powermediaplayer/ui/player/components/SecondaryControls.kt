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

    // WRITE_SETTINGS permission state — hoisted ABOVE the slider so it can
    // gate the slider's `enabled`. Re-checks on every ON_RESUME so the slider
    // un-greys the moment permission is granted in Settings (and re-disables
    // if revoked). Changing system brightness requires WRITE_SETTINGS; without
    // it the slider must be disabled (not just a silent no-op write).
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
                tint = if (brightnessEnabled && canWrite) TextSecondary else DisabledGrey,
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
                // Disabled (greyed + not draggable) until WRITE_SETTINGS is
                // granted — moving system brightness without it is impossible.
                enabled = brightnessEnabled && canWrite,
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
                tint = if (brightnessEnabled && canWrite) TextSecondary else DisabledGrey,
                modifier = Modifier.size(20.dp)
            )
        }

        // WRITE_SETTINGS permission hint — `canWrite` (hoisted above the
        // slider) re-checks on every ON_RESUME, so this banner disappears as
        // soon as the user grants permission and the slider un-greys together.
        if (!canWrite) {
            Text(
                text = "Tap to grant brightness permission",
                style = MaterialTheme.typography.bodyMedium, // up from bodySmall for legibility
                color = TealAccent,
                modifier = Modifier
                    // #1 — clickable BEFORE padding so the 28dp lead margin is
                    // INSIDE the hit region (it was a dead zone), plus a >=48dp
                    // touch target. Folded clearance below the bottom-nav overlay
                    // is already reserved by the parent controls Column's 80dp
                    // bottomBarInset (PlayerScreen.OverlayContent).
                    .clickable { BrightnessHelper.requestWriteSettingsPermission(context) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(start = 28.dp, top = 6.dp, bottom = 6.dp)
            )
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
            // widthIn(min=...) grows with the user's font scale - a hard
            // width(110.dp) clips "1.75x" / "2.5x" at scale >= 1.4x
            // (audit 6.10: this fix originally landed in a component
            // nothing called; ported to the live one, dead twin deleted).
            modifier = Modifier.widthIn(min = 110.dp)
        ) {
            OutlinedTextField(
                value = formatSpeed(playbackSpeed),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = enabled && speedMenuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                    .heightIn(min = 48.dp),
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
    // Item 2 (2026-07-09): binary floats cannot represent values like 1.3 —
    // slider-produced speeds printed as "1.3000001x". Round to 2 dp and trim:
    // 1.0 → "1x", 1.3 → "1.3x", 1.25 → "1.25x".
    val r = kotlin.math.round(speed * 100f) / 100f
    return if (r == r.toLong().toFloat()) "${r.toLong()}x"
        else "%.2f".format(java.util.Locale.UK, r).trimEnd('0').trimEnd('.') + "x"
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
