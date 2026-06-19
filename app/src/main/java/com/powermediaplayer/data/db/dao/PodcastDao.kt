package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcast_shows ORDER BY title ASC")
    fun observeShows(): Flow<List<PodcastShowEntity>>

    @Query("SELECT * FROM podcast_shows WHERE feedUrl = :url LIMIT 1")
    suspend fun getShow(url: String): PodcastShowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShow(show: PodcastShowEntity)

    @Query("DELETE FROM podcast_shows WHERE feedUrl = :url")
    suspend fun unsubscribe(url: String)

    @Query("DELETE FROM podcast_episodes WHERE feedUrl = :url")
    suspend fun deleteEpisodesForFeed(url: String)

    @Query(
        "SELECT * FROM podcast_episodes WHERE feedUrl = :url " +
            "ORDER BY publishedAt DESC LIMIT :limit"
    )
    fun observeEpisodes(url: String, limit: Int = 200): Flow<List<PodcastEpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<PodcastEpisodeEntity>)

    @Query("UPDATE podcast_episodes SET isPlayed = :played WHERE guid = :guid")
    suspend fun setPlayed(guid: String, played: Boolean)

    @Query("SELECT COUNT(*) FROM podcast_shows")
    suspend fun showCount(): Int

    /** Per-show episode totals + "new" (never-opened) count, for the show row. */
    data class FeedCounts(val feedUrl: String, val total: Int, val unopened: Int)

    @Query(
        "SELECT feedUrl, COUNT(*) AS total, " +
            "SUM(CASE WHEN isPlayed = 0 THEN 1 ELSE 0 END) AS unopened " +
            "FROM podcast_episodes GROUP BY feedUrl"
    )
    fun observeFeedCounts(): Flow<List<FeedCounts>>
}
