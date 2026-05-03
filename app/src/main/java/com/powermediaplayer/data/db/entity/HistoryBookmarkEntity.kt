package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-session bookmark snapshot, owned by a single [PlaybackHistoryEntity]
 * row. Mirrors a Player-tab bookmark add at the time it occurs but is
 * independent thereafter — deleting the Player's [BookmarkEntity] does
 * NOT touch this table, and deleting a row here does not affect the
 * Player. The Last Played Recents section renders these per row.
 */
@Entity(
    tableName = "history_bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = PlaybackHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("historyId")]
)
data class HistoryBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val historyId: Long,
    val positionMs: Long,
    val label: String,
    val createdAtMs: Long = System.currentTimeMillis()
)
