package com.powermediaplayer.data.db.dao

import androidx.room.*
import com.powermediaplayer.data.db.entity.PlaybackStateEntity

/**
 * Data access object for saving/restoring playback state.
 * Uses a singleton row pattern (id = 1).
 */
@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun getPlaybackState(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaybackState(state: PlaybackStateEntity)

    @Query("DELETE FROM playback_state")
    suspend fun clearPlaybackState()
}
