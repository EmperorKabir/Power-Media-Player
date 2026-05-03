package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.HistoryFavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryFavouriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: HistoryFavouriteEntity)

    @Query("SELECT * FROM history_favourites ORDER BY pinOrder ASC")
    fun observeAll(): Flow<List<HistoryFavouriteEntity>>

    @Query("DELETE FROM history_favourites WHERE mediaUri = :uri")
    suspend fun unpin(uri: String)

    @Query("UPDATE history_favourites SET pinOrder = :order WHERE mediaUri = :uri")
    suspend fun setOrder(uri: String, order: Int)

    @Query("SELECT COUNT(*) FROM history_favourites")
    suspend fun count(): Int

    @Query("SELECT * FROM history_favourites ORDER BY pinOrder ASC")
    suspend fun snapshot(): List<HistoryFavouriteEntity>
}
