package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.powermediaplayer.data.db.entity.HistoryBookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryBookmarkDao {
    @Insert
    suspend fun insert(row: HistoryBookmarkEntity): Long

    @Query(
        "SELECT * FROM history_bookmarks WHERE historyId = :historyId " +
            "ORDER BY positionMs ASC"
    )
    fun observeForSession(historyId: Long): Flow<List<HistoryBookmarkEntity>>

    @Query(
        "SELECT * FROM history_bookmarks WHERE historyId = :historyId " +
            "ORDER BY positionMs ASC"
    )
    suspend fun snapshotForSession(historyId: Long): List<HistoryBookmarkEntity>

    @Query("DELETE FROM history_bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}
