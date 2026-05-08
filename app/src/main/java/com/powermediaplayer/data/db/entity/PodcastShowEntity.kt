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
    val subscribedAt: Long = System.currentTimeMillis()
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
    val isPlayed: Boolean = false
)
