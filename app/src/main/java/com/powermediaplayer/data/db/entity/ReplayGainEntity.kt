package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * §C18 — pre-scanned ReplayGain values per file. Track gain is the
 * file's own gain; album gain is shared across every file in the same
 * album so volume stays consistent across an album playback.
 *
 * NaN-equivalent represented as `Double.MAX_VALUE` because Room's
 * SQLite storage chokes on NaN under some platform builds. Consumers
 * treat MAX_VALUE as "absent" and fall back to volume = 1.0.
 */
@Entity(tableName = "replay_gain")
data class ReplayGainEntity(
    @PrimaryKey
    val mediaUri: String,
    val trackGainDb: Double,
    val albumGainDb: Double,
    val albumKey: String,
    val scannedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ABSENT = Double.MAX_VALUE
    }
}
