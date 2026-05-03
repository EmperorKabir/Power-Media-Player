package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.powermediaplayer.data.db.entity.HistoryFavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryFavouriteDao {
    @Insert
    suspend fun insert(row: HistoryFavouriteEntity): Long

    @Query("SELECT * FROM history_favourites ORDER BY pinOrder ASC")
    fun observeAll(): Flow<List<HistoryFavouriteEntity>>

    @Query("DELETE FROM history_favourites WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE history_favourites SET pinOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Query("SELECT COUNT(*) FROM history_favourites")
    suspend fun count(): Int

    @Query("SELECT * FROM history_favourites ORDER BY pinOrder ASC")
    suspend fun snapshot(): List<HistoryFavouriteEntity>
}
