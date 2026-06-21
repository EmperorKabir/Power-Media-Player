package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcast_shows")
data class PodcastShowEntity(
    @PrimaryKey
    val feedUrl: String,
    val title: String,
    val artworkUrl: String? = null,
    val description: String = "",
    val lastChecked: Long = 0L,
    val subscribedAt: Long = System.currentTimeMillis(),
    // §C10 per-show settings (LOCKED).
    val autoDownload: Boolean = false,
    val retentionLastN: Int = 0,            // 0 = keep all
    val notifyOnNewEpisode: Boolean = false,
    // User-chosen download folder for THIS show (a persisted SAF tree uri);
    // null → the global default folder (per-show storage override).
    val downloadTreeUri: String? = null
)

@Entity(tableName = "podcast_episodes")
data class PodcastEpisodeEntity(
    @PrimaryKey
    val guid: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val durationS: Long = 0L,
    val publishedAt: Long = 0L,
    val isPlayed: Boolean = false,
    // Offline download tracking: localPath = file:// or content:// uri of the
    // downloaded audio (null = not downloaded); size in bytes + when fetched.
    val localPath: String? = null,
    val localBytes: Long = 0L,
    val downloadedAt: Long = 0L
)
