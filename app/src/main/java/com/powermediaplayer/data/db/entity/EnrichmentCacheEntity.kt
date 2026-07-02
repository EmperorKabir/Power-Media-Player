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
) {
    companion object {
        /**
         * Sentinel artworkUrl meaning "this file was fully parsed and CONFIRMED to
         * carry no embedded cover". Distinguishes 'known artless — never re-fetch'
         * from 'never attempted / fetch failed — retry later' (artworkUrl == null).
         * Excluded from observeCovered so it never renders.
         */
        const val NO_ART = "noart"

        /** Row written by DriveTagEnricher after a FULL parse — complete evidence
         *  (tags AND art/no-art), so favourite-enrich never repeats it. */
        const val PROVIDER_DRIVE_FULL = "drive-fav-tags"

        /** Row written by the browse-time art peek / ArtworkCache backfill — art
         *  only, no search tags, so favourite-enrich still runs the full parse. */
        const val PROVIDER_DRIVE_PEEK = "drive-art-peek"
    }
}
