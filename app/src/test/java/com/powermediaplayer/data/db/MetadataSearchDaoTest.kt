package com.powermediaplayer.data.db

import androidx.room.Room
import com.powermediaplayer.data.db.entity.EnrichmentCacheEntity
import com.powermediaplayer.data.db.entity.OfflineCopyEntity
import com.powermediaplayer.data.db.entity.PlaybackHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #16 — the enriched-metadata LIKE queries against real Room (in-memory). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MetadataSearchDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun searchDriveMetadata_matchesAuthorInSubtitle() = runBlocking {
        db.playbackHistoryDao().insertSession(
            PlaybackHistoryEntity(
                mediaUri = "https://drive/file1", title = "This Inevitable Ruin",
                subtitle = "Matt Dinniman", artworkUri = null, source = "DRIVE",
                mediaKindOrdinal = 0, lastPositionMs = 0, durationMs = 0, lastPlayedAt = 1
            )
        )
        // A non-Drive row must NOT match.
        db.playbackHistoryDao().insertSession(
            PlaybackHistoryEntity(
                mediaUri = "content://x", title = "Matt local", subtitle = "", artworkUri = null,
                source = "LOCAL", mediaKindOrdinal = 0, lastPositionMs = 0, durationMs = 0, lastPlayedAt = 2
            )
        )
        val hits = db.playbackHistoryDao().searchDriveMetadata("%matt%")
        assertEquals(1, hits.size)
        assertEquals("https://drive/file1", hits.first().mediaUri)
    }

    @Test fun searchDisplayName_offlineCopy() = runBlocking {
        db.offlineCopyDao().upsert(
            OfflineCopyEntity(
                driveFileId = "id1", localPath = "/x", byteSize = 1,
                displayName = "This Inevitable Ruin — Matt Dinniman.m4b"
            )
        )
        assertEquals(1, db.offlineCopyDao().searchDisplayName("%matt%").size)
        assertEquals(0, db.offlineCopyDao().searchDisplayName("%zzz%").size)
    }

    @Test fun searchEnriched_titleArtistAlbumGenre() = runBlocking {
        db.enrichmentCacheDao().put(
            EnrichmentCacheEntity(
                cacheKey = "k", provider = "mb", title = "Ruin", artist = "Matt Dinniman",
                album = "Dungeon Crawler Carl", year = null, genre = null,
                artworkUrl = null, fetchedAtMs = 1
            )
        )
        assertEquals(1, db.enrichmentCacheDao().searchEnriched("%matt%").size)   // artist
        assertEquals(1, db.enrichmentCacheDao().searchEnriched("%crawler%").size) // album/series
    }

    /**
     * #16 B4 — the entity-declared covering indices must materialise with the
     * exact names MIGRATION_20_21 creates (Room's index_<table>_<column>
     * convention). exportSchema=false means Room validates the migrated DB's
     * schema against the entity-derived schema at runtime; matching names here
     * is what keeps a real on-device v20→v21 upgrade from throwing.
     */
    @Test fun coveringIndicesExist_forMetadataSearch() {
        val names = HashSet<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='index'").use { c ->
                val i = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) names.add(c.getString(i))
            }
        assertTrue("index_playback_history_title", names.contains("index_playback_history_title"))
        assertTrue("index_playback_history_subtitle", names.contains("index_playback_history_subtitle"))
        assertTrue("index_offline_copy_displayName", names.contains("index_offline_copy_displayName"))
        assertTrue("index_enrichment_cache_title", names.contains("index_enrichment_cache_title"))
    }
}
