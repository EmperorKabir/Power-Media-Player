package com.powermediaplayer.util

/**
 * Formats millisecond durations into human-readable time strings.
 */
object TimeFormatter {

    /**
     * Format milliseconds into MM:SS or H:MM:SS format.
     * Returns "0:00" for zero or negative values.
     */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"

        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        // Locale.US so Arabic-numeral / RTL devices still produce
        // ASCII digits (the duration is parsed by other code paths).
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Format minutes into a friendly display string.
     * e.g., 90 -> "1h 30m", 45 -> "45m"
     */
    fun formatMinutes(minutes: Int): String {
        return when {
            minutes <= 0 -> "Off"
            minutes < 60 -> "${minutes}m"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}
