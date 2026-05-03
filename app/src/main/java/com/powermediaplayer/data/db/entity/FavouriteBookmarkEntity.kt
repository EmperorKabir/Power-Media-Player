package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bookmark snapshot owned by a pinned [HistoryFavouriteEntity]. Created
 * by copy at pin time from the corresponding Recents row's
 * [HistoryBookmarkEntity]s, then independent — deleting Player or
 * Recents bookmarks does not affect this table. Pinned bookmarks can
 * only be deleted from within the Pinned section's own UI.
 */
@Entity(
    tableName = "favourite_bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = HistoryFavouriteEntity::class,
            parentColumns = ["id"],
            childColumns = ["favouriteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("favouriteId")]
)
data class FavouriteBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val favouriteId: Long,
    val positionMs: Long,
    val label: String,
    val createdAtMs: Long = System.currentTimeMillis()
)
