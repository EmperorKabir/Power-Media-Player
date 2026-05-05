package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-file timestamp bookmark. mediaUri keys the file (matches
 * MediaItem.mediaId), positionMs is where to seek when tapped.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index("mediaUri")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUri: String,
    val positionMs: Long,
    val label: String,
    val createdAtMs: Long = System.currentTimeMillis()
)
