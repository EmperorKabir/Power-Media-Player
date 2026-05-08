package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import kotlinx.coroutines.flow.Flow

/**
 * §C7 — DAO for per-file playback overrides.
 *
 * `getByUri` is exposed as a Flow so the player can react live when the
 * user opens the override popup and edits an axis — no need to re-poll
 * on every transition.
 */
@Dao
interface MediaOverrideDao {

    @Query("SELECT * FROM media_overrides WHERE mediaUri = :uri LIMIT 1")
    fun getByUri(uri: String): Flow<MediaOverrideEntity?>

    @Query("SELECT * FROM media_overrides WHERE mediaUri = :uri LIMIT 1")
    suspend fun getByUriOnce(uri: String): MediaOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaOverrideEntity)

    @Query("DELETE FROM media_overrides WHERE mediaUri = :uri")
    suspend fun clear(uri: String)

    @Query("DELETE FROM media_overrides")
    suspend fun clearAll()

    @Query("SELECT mediaUri FROM media_overrides")
    suspend fun allUris(): List<String>
}
