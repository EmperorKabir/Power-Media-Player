package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.DisabledGrey
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextSecondary

/**
 * #4 — parses "h:mm:ss", "m:ss", or bare seconds (digits only) into
 * milliseconds. Returns null on malformed input or any negative value. Pure —
 * unit-testable with no Android deps (see SeekTimeParserTest).
 */
fun parseTimeToMs(raw: String): Long? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val parts = s.split(":")
    if (parts.size > 3) return null
    val nums = parts.map { it.toLongOrNull() ?: return null }
    if (nums.any { it < 0 }) return null
    val (h, m, sec) = when (nums.size) {
        1 -> Triple(0L, 0L, nums[0])
        2 -> Triple(0L, nums[0], nums[1])
        else -> Triple(nums[0], nums[1], nums[2])
    }
    // The seconds field of a colon-form must be < 60; the minutes field of an
    // h:mm:ss form must be < 60. Bare seconds may be any magnitude.
    if (nums.size >= 2 && sec >= 60) return null
    if (nums.size == 3 && m >= 60) return null
    return (h * 3600 + m * 60 + sec) * 1000L
}

/**
 * Formats raw digit input into a right-aligned m:ss / h:mm:ss display, so the
 * numeric keypad (which has no colon key) still produces colon-separated time.
 * The user types digits only; colons appear automatically from the right
 * (ss, then mm, then h). Output feeds [parseTimeToMs], which validates the
 * <60 bounds on the seconds/minutes fields. Capped at 6 digits (hh:mm:ss).
 */
fun maskTimeDigits(raw: String): String {
    val d = raw.filter { it.isDigit() }.takeLast(6)
    return when {
        d.length <= 2 -> d
        d.length <= 4 -> d.dropLast(2) + ":" + d.takeLast(2)
        else -> d.dropLast(4) + ":" + d.substring(d.length - 4, d.length - 2) + ":" + d.takeLast(2)
    }
}

/**
 * #4 — numeric time-entry dialog. Tapping the elapsed/remaining time labels on
 * either player layout opens this; the caller supplies the title, the current
 * formatted value as a hint, the seekable ceiling, and the seek action. One
 * composable serves both layouts (single PositionSection call site).
 */
@Composable
fun SeekTimeDialog(
    title: String,
    currentLabel: String,
    maxMs: Long,
    onConfirmMs: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val parsed = parseTimeToMs(text)
    val valid = parsed != null && parsed in 0..maxMs
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = TealAccent) },
        text = {
            Column {
                Text(
                    "Current: $currentLabel. Type digits — they fill in as m:ss then h:mm:ss.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = text,
                    // Numeric keypad has no colon key; auto-insert colons from the
                    // digits the user types so h:mm:ss is enterable without one.
                    onValueChange = { text = maskTimeDigits(it) },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text("e.g. 12:30") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirmMs(it); onDismiss() } },
                enabled = valid
            ) { Text("Jump", color = if (valid) TealAccent else DisabledGrey) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}
