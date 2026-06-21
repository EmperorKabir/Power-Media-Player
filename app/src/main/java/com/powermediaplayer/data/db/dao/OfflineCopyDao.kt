package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.OfflineCopyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineCopyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OfflineCopyEntity)

    @Query("SELECT * FROM offline_copy WHERE driveFileId = :id LIMIT 1")
    suspend fun get(id: String): OfflineCopyEntity?

    @Query("SELECT * FROM offline_copy")
    fun observeAll(): Flow<List<OfflineCopyEntity>>

    @Query("SELECT * FROM offline_copy ORDER BY lastPlayedAt ASC")
    suspend fun lruSnapshot(): List<OfflineCopyEntity>

    @Query("SELECT IFNULL(SUM(byteSize), 0) FROM offline_copy")
    suspend fun totalBytes(): Long

    @Query("UPDATE offline_copy SET lastPlayedAt = :ts WHERE driveFileId = :id")
    suspend fun touch(id: String, ts: Long = System.currentTimeMillis())

    /** §C28 — pin/unpin a copy so the LRU evictor never reclaims it. */
    @Query("UPDATE offline_copy SET isStarred = :starred WHERE driveFileId = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("DELETE FROM offline_copy WHERE driveFileId = :id")
    suspend fun delete(id: String)
}
