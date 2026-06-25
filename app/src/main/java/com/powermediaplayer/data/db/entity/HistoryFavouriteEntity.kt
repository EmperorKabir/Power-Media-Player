package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Pinned snapshot. Carries its OWN copy of title / subtitle / artwork /
 * source / lastPositionMs so deletion of the underlying playback_history
 * row does NOT delete the pin. Bookmarks are snapshotted separately
 * into [FavouriteBookmarkEntity] at pin time and are independent of
 * the original session's bookmarks. Max 10 rows enforced at the
 * repository layer; user-reorderable via [pinOrder].
 */
@Entity(
    tableName = "history_favourites",
    indices = [Index("pinOrder")]
)
data class HistoryFavouriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUri: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val source: String,
    val mediaKindOrdinal: Int,
    val lastPositionMs: Long,
    val durationMs: Long,
    val pinOrder: Int,
    val pinnedAtMs: Long,
    // #19 — when true, playing this pin resumes from the LIVE playback_history
    // position (resolved at play time) instead of the snapshot frozen at pin
    // time. Default false = existing fixed-snapshot behaviour. Additive (v20).
    val followLive: Boolean = false
)
