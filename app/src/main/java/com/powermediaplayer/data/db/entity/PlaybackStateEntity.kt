package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting playback state across app restarts.
 * Stores the last playing media URI, position, and playlist index.
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey
    val id: Int = 1, // Singleton row
    val mediaUri: String = "",
    val position: Long = 0L,
    val playlistIndex: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
