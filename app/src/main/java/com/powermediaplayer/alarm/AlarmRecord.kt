package com.powermediaplayer.alarm

/**
 * §C12 — single scheduled alarm. Persisted as a "id|hour|minute|days|mediaUri|enabled"
 * string in the DataStore SCHEDULED_ALARMS set.
 *
 * - [days]: 7-bit mask. Bit 0 = Mon, bit 1 = Tue, … bit 6 = Sun. 0 = one-shot.
 * - [mediaUri]: optional content:// or file:// URI to play. Empty string =
 *   play whatever was last queued (resume mode).
 * - [enabled]: lets the user toggle a saved alarm without deleting it.
 *
 * Wake-up alarm full-screen activity, snooze, days-skip-N, math-problem
 * stop, and volume-ramp are deferred — this is the v0.1 useful baseline.
 */
data class AlarmRecord(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val mediaUri: String,
    val enabled: Boolean
) {
    fun serialize(): String =
        "$id|$hour|$minute|$days|$mediaUri|${if (enabled) 1 else 0}"

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
            val parts = s.split('|', limit = 6)
            if (parts.size < 6) return null
            return AlarmRecord(
                id = parts[0].toLongOrNull() ?: return null,
                hour = parts[1].toIntOrNull()?.coerceIn(0, 23) ?: return null,
                minute = parts[2].toIntOrNull()?.coerceIn(0, 59) ?: return null,
                days = parts[3].toIntOrNull()?.coerceIn(0, 127) ?: return null,
                mediaUri = parts[4],
                enabled = parts[5] == "1"
            )
        }
    }
}
