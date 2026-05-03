package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PlaybackHistoryEntity)

    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC")
    fun observeAll(): Flow<List<PlaybackHistoryEntity>>

    /** One-shot snapshot — used by trimToCap so we don't subscribe a Flow per recordPlay. */
    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC")
    suspend fun snapshot(): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun mostRecent(): PlaybackHistoryEntity?

    @Query("UPDATE playback_history SET lastPositionMs = :pos WHERE mediaUri = :uri")
    suspend fun updatePosition(uri: String, pos: Long)

    @Query("DELETE FROM playback_history WHERE mediaUri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM playback_history WHERE mediaUri IN (:uris)")
    suspend fun deleteMany(uris: List<String>)

    @Query("SELECT * FROM playback_history WHERE mediaUri = :uri")
    suspend fun get(uri: String): PlaybackHistoryEntity?
}
