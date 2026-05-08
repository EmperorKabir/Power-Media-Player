package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.EnrichmentCacheEntity

@Dao
interface EnrichmentCacheDao {
    @Query("SELECT * FROM enrichment_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): EnrichmentCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: EnrichmentCacheEntity)

    @Query("DELETE FROM enrichment_cache WHERE fetchedAtMs < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long): Int

    @Query("SELECT COUNT(*) FROM enrichment_cache")
    suspend fun count(): Int
}
