package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.ReplayGainEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplayGainDao {
    @Query("SELECT * FROM replay_gain WHERE mediaUri = :uri LIMIT 1")
    suspend fun getForUri(uri: String): ReplayGainEntity?

    @Query("SELECT * FROM replay_gain WHERE mediaUri = :uri LIMIT 1")
    fun observeForUri(uri: String): Flow<ReplayGainEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ReplayGainEntity)

    @Query("DELETE FROM replay_gain")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM replay_gain")
    suspend fun count(): Int
}
