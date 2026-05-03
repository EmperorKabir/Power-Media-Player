package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pin marker for a [PlaybackHistoryEntity]. Pinned items are
 * protected from the 10-item history cap and are user-reorderable
 * via [pinOrder]. Max 10 rows enforced at the repository layer.
 */
@Entity(tableName = "history_favourites")
data class HistoryFavouriteEntity(
    @PrimaryKey val mediaUri: String,
    val pinOrder: Int
)
