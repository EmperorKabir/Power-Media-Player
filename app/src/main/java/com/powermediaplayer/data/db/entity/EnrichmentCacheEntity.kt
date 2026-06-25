package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §C17 A11.4 — cached MusicBrainz / Discogs lookup results, keyed
 * on the lower-cased "artist|album|title" tuple. Subsequent lookups
 * for the same key skip the network entirely.
 */
// #16 — title indexed to back the enriched-metadata Drive search.
@Entity(tableName = "enrichment_cache", indices = [Index("title")])
data class EnrichmentCacheEntity(
    @PrimaryKey val cacheKey: String,
    val provider: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val genre: String?,
    val artworkUrl: String?,
    val fetchedAtMs: Long
)
