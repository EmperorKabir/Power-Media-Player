package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM podcast_episodes WHERE guid = :guid LIMIT 1")
    suspend fun getEpisode(guid: String): PodcastEpisodeEntity?

    /**
     * Feed re-sync that PRESERVES per-episode user state. A blanket REPLACE
     * upsert would reset isPlayed AND wipe the offline-download columns
     * (localPath/localBytes/downloadedAt) every time the RSS feed is re-fetched,
     * orphaning downloaded files. This carries the existing state forward for
     * matching guids; brand-new episodes insert as parsed.
     */
    @Transaction
    suspend fun syncEpisodes(episodes: List<PodcastEpisodeEntity>) {
        val merged = episodes.map { e ->
            val existing = getEpisode(e.guid)
            if (existing == null) e else e.copy(
                isPlayed = existing.isPlayed,
                localPath = existing.localPath,
                localBytes = existing.localBytes,
                downloadedAt = existing.downloadedAt
            )
        }
        upsertEpisodes(merged)
    }

    @Query("UPDATE podcast_episodes SET isPlayed = :played WHERE guid = :guid")
    suspend fun setPlayed(guid: String, played: Boolean)

    // §C10 downloads — offline-copy bookkeeping.
    @Query(
        "UPDATE podcast_episodes SET localPath = :path, localBytes = :bytes, " +
            "downloadedAt = :at WHERE guid = :guid"
    )
    suspend fun setLocalPath(guid: String, path: String?, bytes: Long, at: Long)

    @Query(
        "UPDATE podcast_episodes SET localPath = NULL, localBytes = 0, " +
            "downloadedAt = 0 WHERE guid = :guid"
    )
    suspend fun clearLocalPath(guid: String)

    @Query("SELECT * FROM podcast_episodes WHERE localPath IS NOT NULL ORDER BY downloadedAt DESC")
    fun observeDownloaded(): Flow<List<PodcastEpisodeEntity>>

    @Query("SELECT * FROM podcast_episodes WHERE feedUrl = :url AND localPath IS NOT NULL")
    suspend fun downloadedForFeed(url: String): List<PodcastEpisodeEntity>

    /** A downloaded episode keyed by its STREAM url — lets any play path (cold-start
     *  resume, Recents tap) map the stored stream uri back to the local file. */
    @Query("SELECT * FROM podcast_episodes WHERE audioUrl = :url AND localPath IS NOT NULL LIMIT 1")
    suspend fun downloadedByAudioUrl(url: String): PodcastEpisodeEntity?

    /** Any episode (downloaded or not) by its stream url — lets a Last Played /
     *  Player offline action find the episode + show to download on demand. */
    @Query("SELECT * FROM podcast_episodes WHERE audioUrl = :url LIMIT 1")
    suspend fun episodeByAudioUrl(url: String): PodcastEpisodeEntity?

    @Query("SELECT IFNULL(SUM(localBytes), 0) FROM podcast_episodes WHERE localPath IS NOT NULL")
    suspend fun totalDownloadedBytes(): Long

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
