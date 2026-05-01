package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent flag marking a media file as a favorite. Keyed by URI string —
 * MediaStore IDs are device-local and shift when the file is re-indexed,
 * but the content URI is stable for a given file path.
 */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey
    val uri: String,
    val addedAt: Long = System.currentTimeMillis()
)
