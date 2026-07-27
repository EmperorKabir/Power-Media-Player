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

    /** Every enriched row that carries a REAL cover (the confirmed-artless sentinel
     *  is excluded), so an enriched Drive item can show its extracted cover in the
     *  Cloud list even before the full file is downloaded. */
    @Query(
        "SELECT * FROM enrichment_cache WHERE artworkUrl IS NOT NULL " +
            "AND artworkUrl != '${EnrichmentCacheEntity.NO_ART}'"
    )
    fun observeCovered(): kotlinx.coroutines.flow.Flow<List<EnrichmentCacheEntity>>

    /** Every row that carries an enriched TITLE (regardless of artwork), so a Drive
     *  row can display Title + Artist/Album subtext instead of the raw filename. */
    @Query("SELECT * FROM enrichment_cache WHERE title IS NOT NULL AND title != ''")
    fun observeEnriched(): kotlinx.coroutines.flow.Flow<List<EnrichmentCacheEntity>>

    /** #16 — search the full enriched field set (title/artist/album/genre) so an
     *  author (artist), series (album) or narrator/genre search all hit. */
    @Query(
        "SELECT * FROM enrichment_cache " +
            "WHERE title LIKE :pattern ESCAPE '\\' OR artist LIKE :pattern ESCAPE '\\' " +
            "OR album LIKE :pattern ESCAPE '\\' OR genre LIKE :pattern ESCAPE '\\' LIMIT 50"
    )
    suspend fun searchEnriched(pattern: String): List<EnrichmentCacheEntity>
}
