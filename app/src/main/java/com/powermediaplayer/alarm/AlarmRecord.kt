package com.powermediaplayer.alarm

/**
 * §C12 — single scheduled alarm. Persisted as a delimited string in
 * the DataStore SCHEDULED_ALARMS set. Backwards-compatible: the v1
 * format had 6 fields (id|hour|minute|days|mediaUri|enabled) so we
 * deserialise both forms — extra fields fall back to defaults when
 * reading a legacy record.
 *
 * Locked fields per §C12:
 *  - [days]: 7-bit mask (bit 0 = Mon, bit 6 = Sun). 0 = one-shot.
 *  - [mediaUri]: optional content:// or file:// URI to play. Empty
 *    string = play whatever was last queued (resume mode).
 *  - [enabled]: lets the user toggle a saved alarm without deleting.
 *  - [startVolumePct] / [endVolumePct] / [rampSeconds]: volume ramp.
 *    Direction is implicit: start < end → rises, start > end → falls.
 *  - [holdMinutes]: how long to ring at end-volume after the ramp
 *    completes. -1 = indefinite (no auto-stop without user action).
 *  - [windDownSeconds]: post-hold fade-to-zero. 0 = abrupt cut.
 *  - [snoozeEnabled] / [snoozeMinutes] / [maxSnoozes]: snooze policy.
 *    maxSnoozes = -1 means unlimited.
 *  - [snoozeRestartFromStart]: true → snooze restarts the ramp;
 *    false → snooze resumes at end-volume.
 *  - [skipNextCount]: numeric picker 0..7. Decremented on each fire.
 *  - [stopMethod]: TAP / SHAKE / MATH.
 *  - [vibration]: vibrate while ringing.
 *  - [displayLabel]: friendly label for the row in [AlarmsSheet]
 *    (replaces the raw URI fragment).
 */
data class AlarmRecord(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val mediaUri: String,
    val enabled: Boolean,
    val startVolumePct: Int = 10,
    val endVolumePct: Int = 80,
    val rampSeconds: Int = 60,
    val holdMinutes: Int = 30,
    val windDownSeconds: Int = 60,
    val snoozeEnabled: Boolean = true,
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 5,
    val snoozeRestartFromStart: Boolean = false,
    val skipNextCount: Int = 0,
    val stopMethod: StopMethod = StopMethod.TAP,
    val vibration: Boolean = true,
    val displayLabel: String = ""
) {
    enum class StopMethod { TAP, SHAKE, MATH }

    fun serialize(): String = listOf(
        id.toString(),
        hour.toString(),
        minute.toString(),
        days.toString(),
        // mediaUri may contain '|' in rare cases — encode the bar.
        mediaUri.replace("|", "%7C"),
        if (enabled) "1" else "0",
        startVolumePct.toString(),
        endVolumePct.toString(),
        rampSeconds.toString(),
        holdMinutes.toString(),
        windDownSeconds.toString(),
        if (snoozeEnabled) "1" else "0",
        snoozeMinutes.toString(),
        maxSnoozes.toString(),
        if (snoozeRestartFromStart) "1" else "0",
        skipNextCount.toString(),
        stopMethod.name,
        if (vibration) "1" else "0",
        displayLabel.replace("|", "%7C")
    ).joinToString("|")

    val timeLabel: String
        get() = "%02d:%02d".format(hour, minute)

    val daysLabel: String
        get() = if (days == 0) "Once"
        else listOfNotNull(
            if (days and 1 != 0) "Mon" else null,
            if (days and 2 != 0) "Tue" else null,
            if (days and 4 != 0) "Wed" else null,
            if (days and 8 != 0) "Thu" else null,
            if (days and 16 != 0) "Fri" else null,
            if (days and 32 != 0) "Sat" else null,
            if (days and 64 != 0) "Sun" else null
        ).joinToString(" / ")

    companion object {
        fun deserialize(s: String): AlarmRecord? {
            val parts = s.split('|')
            if (parts.size < 6) return null
            return AlarmRecord(
                id = parts[0].toLongOrNull() ?: return null,
                hour = parts[1].toIntOrNull()?.coerceIn(0, 23) ?: return null,
                minute = parts[2].toIntOrNull()?.coerceIn(0, 59) ?: return null,
                days = parts[3].toIntOrNull()?.coerceIn(0, 127) ?: return null,
                mediaUri = parts[4].replace("%7C", "|"),
                enabled = parts[5] == "1",
                startVolumePct = parts.getOrNull(6)?.toIntOrNull()?.coerceIn(0, 100) ?: 10,
                endVolumePct = parts.getOrNull(7)?.toIntOrNull()?.coerceIn(0, 100) ?: 80,
                rampSeconds = parts.getOrNull(8)?.toIntOrNull()?.coerceAtLeast(0) ?: 60,
                holdMinutes = parts.getOrNull(9)?.toIntOrNull() ?: 30,
                windDownSeconds = parts.getOrNull(10)?.toIntOrNull()?.coerceAtLeast(0) ?: 60,
                snoozeEnabled = parts.getOrNull(11)?.let { it == "1" } ?: true,
                snoozeMinutes = parts.getOrNull(12)?.toIntOrNull()?.coerceIn(1, 60) ?: 5,
                maxSnoozes = parts.getOrNull(13)?.toIntOrNull() ?: 5,
                snoozeRestartFromStart = parts.getOrNull(14)?.let { it == "1" } ?: false,
                skipNextCount = parts.getOrNull(15)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                stopMethod = parts.getOrNull(16)?.let {
                    runCatching { StopMethod.valueOf(it) }.getOrNull()
                } ?: StopMethod.TAP,
                vibration = parts.getOrNull(17)?.let { it == "1" } ?: true,
                displayLabel = parts.getOrNull(18)?.replace("%7C", "|") ?: ""
            )
        }
    }
}
