package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * §C6 — smart playlist. Rules + sort + limit are stored as a single
 * JSON blob to avoid a normalised "rules" table — the playlist count
 * is capped at 20 so the column is small. Resolving happens at tap-
 * into time via [com.powermediaplayer.playlist.SmartPlaylistResolver].
 *
 * Rules JSON shape:
 *   {
 *     "rules": [
 *       {"field": "artist", "op": "contains", "value": "miles"},
 *       {"field": "playCount", "op": "gte", "value": 3},
 *       {"field": "lastPlayedDays", "op": "lte", "value": 30}
 *     ],
 *     "sort": "lastPlayed",     // name | dateAdded | lastPlayed | playCount | duration | random
 *     "limit": 50
 *   }
 *
 * Rules combine with AND. Available fields per locked spec:
 *   title / artist / album / genre / year / duration / source /
 *   mediaKind / lastPlayedDays / playCount / hasBookmark / isFavourite
 */
@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val rulesJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
