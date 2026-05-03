package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.powermediaplayer.data.db.entity.FavouriteBookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteBookmarkDao {
    @Insert
    suspend fun insert(row: FavouriteBookmarkEntity): Long

    @Query(
        "SELECT * FROM favourite_bookmarks WHERE favouriteId = :favouriteId " +
            "ORDER BY positionMs ASC"
    )
    fun observeForFavourite(favouriteId: Long): Flow<List<FavouriteBookmarkEntity>>

    @Query("DELETE FROM favourite_bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}
