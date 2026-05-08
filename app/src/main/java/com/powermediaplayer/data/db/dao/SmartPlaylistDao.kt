package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.powermediaplayer.data.db.entity.SmartPlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartPlaylistDao {
    @Query("SELECT * FROM smart_playlists ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT COUNT(*) FROM smart_playlists")
    suspend fun count(): Int

    @Query("SELECT * FROM smart_playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmartPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: SmartPlaylistEntity): Long

    @Update
    suspend fun update(playlist: SmartPlaylistEntity)

    @Delete
    suspend fun delete(playlist: SmartPlaylistEntity)

    @Query("DELETE FROM smart_playlists WHERE id = :id")
    suspend fun deleteById(id: Long)
}
