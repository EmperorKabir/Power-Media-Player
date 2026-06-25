package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per PLAY SESSION. Multiple rows may exist for the same
 * mediaUri (each open-and-play creates a new row). The Last Played
 * tab's Recents section renders these chronologically — so e.g.
 * playing A → B → A produces three separate rows so the user can
 * keep distinct bookmarks per session.
 *
 * source: "LOCAL" | "DRIVE" | "SPOTIFY".
 */
@Entity(
    tableName = "playback_history",
    // #16 — title/subtitle indexed to back the enriched-metadata Drive search.
    indices = [Index("mediaUri"), Index("lastPlayedAt"), Index("title"), Index("subtitle")]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUri: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val source: String,
    val mediaKindOrdinal: Int,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long
)
