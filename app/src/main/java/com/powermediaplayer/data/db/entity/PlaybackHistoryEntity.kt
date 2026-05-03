package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per distinct media item the user has played. Deduped by
 * [mediaUri]; touching an existing item via OnConflictStrategy.REPLACE
 * updates timestamp and position. Used by the Last Played tab.
 *
 * source: "LOCAL" | "DRIVE" | "SPOTIFY".
 */
@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey val mediaUri: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val source: String,
    val mediaKindOrdinal: Int,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long
)
