package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT uri FROM favorite")
    fun observeAllUris(): Flow<List<String>>

    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    suspend fun getAll(): List<FavoriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE uri = :uri)")
    suspend fun isFavorite(uri: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Delete
    suspend fun delete(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
