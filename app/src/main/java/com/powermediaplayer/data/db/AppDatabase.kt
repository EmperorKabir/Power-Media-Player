package com.powermediaplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.powermediaplayer.data.db.dao.BookmarkDao
import com.powermediaplayer.data.db.dao.EnrichmentCacheDao
import com.powermediaplayer.data.db.dao.EqualizerPresetDao
import com.powermediaplayer.data.db.dao.FavoriteDao
import com.powermediaplayer.data.db.dao.FavouriteBookmarkDao
import com.powermediaplayer.data.db.dao.HistoryBookmarkDao
import com.powermediaplayer.data.db.dao.HistoryFavouriteDao
import com.powermediaplayer.data.db.dao.MediaOverrideDao
import com.powermediaplayer.data.db.dao.OfflineCopyDao
import com.powermediaplayer.data.db.dao.PlaybackHistoryDao
import com.powermediaplayer.data.db.dao.PlaybackStateDao
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.dao.ReplayGainDao
import com.powermediaplayer.data.db.dao.SmartPlaylistDao
import com.powermediaplayer.data.db.entity.BookmarkEntity
import com.powermediaplayer.data.db.entity.EnrichmentCacheEntity
import com.powermediaplayer.data.db.entity.EqualizerPresetEntity
import com.powermediaplayer.data.db.entity.FavoriteEntity
import com.powermediaplayer.data.db.entity.FavouriteBookmarkEntity
import com.powermediaplayer.data.db.entity.HistoryBookmarkEntity
import com.powermediaplayer.data.db.entity.HistoryFavouriteEntity
import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import com.powermediaplayer.data.db.entity.OfflineCopyEntity
import com.powermediaplayer.data.db.entity.PlaybackHistoryEntity
import com.powermediaplayer.data.db.entity.PlaybackStateEntity
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.data.db.entity.ReplayGainEntity
import com.powermediaplayer.data.db.entity.PinnedAlbumEntity
import com.powermediaplayer.data.db.entity.PinnedAlbumTrackEntity
import com.powermediaplayer.data.db.entity.SmartPlaylistEntity
import com.powermediaplayer.data.db.dao.PinnedAlbumDao

/**
 * Main Room database for Power Media Player.
 * Contains tables for equalizer presets, playback state, and favorites.
 */
@Database(
    entities = [
        EqualizerPresetEntity::class,
        PlaybackStateEntity::class,
        FavoriteEntity::class,
        BookmarkEntity::class,
        PlaybackHistoryEntity::class,
        HistoryFavouriteEntity::class,
        HistoryBookmarkEntity::class,
        FavouriteBookmarkEntity::class,
        MediaOverrideEntity::class,
        SmartPlaylistEntity::class,
        PodcastShowEntity::class,
        PodcastEpisodeEntity::class,
        ReplayGainEntity::class,
        OfflineCopyEntity::class,
        EnrichmentCacheEntity::class,
        PinnedAlbumEntity::class,
        PinnedAlbumTrackEntity::class
    ],
    // v5: PlaybackHistory + HistoryFavourite switched to autogen IDs;
    // added HistoryBookmark + FavouriteBookmark snapshot tables.
    // v6: BookmarkEntity gained an index on mediaUri (the only read
    // predicate) — bumped to force schema regeneration.
    // v7: re-seed default Equalizer presets with audibility-tuned
    // values (Classical + Acoustic boosted past JND on phone
    // speakers). Destructive migration drops the table so the seed
    // routine re-inserts the new values.
    // v8: §C7 per-file playback overrides — adds `media_overrides`
    // table. First non-destructive migration; existing data
    // preserved.
    // v9: §C6 smart playlists — adds `smart_playlists` table.
    // v10: §C10 podcasts — adds `podcast_shows` + `podcast_episodes`.
    // v11: §C18 ReplayGain pre-scan — adds `replay_gain`.
    // v12: §C10 per-show settings — adds autoDownload + retentionLastN
    //      + notifyOnNewEpisode columns to podcast_shows.
    // v13: §C28 LOCKED `offline_copy` table — replaces the DataStore
    //      Set so byteSize / lastPlayedAt are tracked for LRU eviction.
    // v14: D12 — adds an index on bookmarks.createdAtMs to back the
    //      observeAll() query that orders by it.
    // v15: §C17 A11.4 — adds `enrichment_cache` table so repeat
    //      MusicBrainz / Discogs lookups skip the network.
    // v16: Pinned albums — adds `pinned_albums` + `pinned_album_tracks`
    //      tables. Shares the 10-pin cap with HistoryFavouriteEntity at
    //      the repository layer.
    // v17: §C10 downloads — adds episode localPath/localBytes/downloadedAt +
    //      per-show downloadTreeUri (user-chosen storage). Additive.
    // v18: §C28 — offline_copy gains displayName so the Downloads list shows
    //      the real Drive file name, not the cache filename. Additive.
    version = 18,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun equalizerPresetDao(): EqualizerPresetDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun historyFavouriteDao(): HistoryFavouriteDao
    abstract fun historyBookmarkDao(): HistoryBookmarkDao
    abstract fun favouriteBookmarkDao(): FavouriteBookmarkDao
    abstract fun mediaOverrideDao(): MediaOverrideDao
    abstract fun smartPlaylistDao(): SmartPlaylistDao
    abstract fun podcastDao(): PodcastDao
    abstract fun replayGainDao(): ReplayGainDao
    abstract fun offlineCopyDao(): OfflineCopyDao
    abstract fun enrichmentCacheDao(): EnrichmentCacheDao
    abstract fun pinnedAlbumDao(): PinnedAlbumDao

    companion object {
        const val DATABASE_NAME = "power_media_player.db"

        /**
         * §C7 v7→v8 — adds the `media_overrides` table for per-file
         * playback overrides. Every non-PK column is nullable so a
         * row records only the axes the user has actually overridden.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_overrides (
                        mediaUri TEXT NOT NULL PRIMARY KEY,
                        reverbPreset INTEGER,
                        stereoFlip INTEGER,
                        monoMix INTEGER,
                        eqPresetId INTEGER,
                        replayGainMode TEXT,
                        volumeBoostMb INTEGER,
                        videoFlipH INTEGER,
                        videoFlipV INTEGER,
                        videoBw INTEGER,
                        videoSepia INTEGER,
                        videoInvert INTEGER,
                        videoRotation INTEGER,
                        playbackSpeed REAL,
                        pitch REAL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * §C6 v8→v9 — adds the `smart_playlists` table.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS smart_playlists (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        rulesJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * §C28 v12→v13 — adds the `offline_copy` table.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_copy (
                        driveFileId TEXT NOT NULL PRIMARY KEY,
                        localPath TEXT NOT NULL,
                        byteSize INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastPlayedAt INTEGER NOT NULL,
                        isStarred INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * §C10 v11→v12 — adds per-show settings columns to
         * podcast_shows.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcast_shows ADD COLUMN autoDownload INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcast_shows ADD COLUMN retentionLastN INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcast_shows ADD COLUMN notifyOnNewEpisode INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v16 → v17 (§C10 downloads): episode offline-copy tracking +
        // per-show download folder override. Additive ALTERs; existing
        // rows default to not-downloaded / global-folder.
        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcast_episodes ADD COLUMN localPath TEXT")
                db.execSQL("ALTER TABLE podcast_episodes ADD COLUMN localBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcast_episodes ADD COLUMN downloadedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcast_shows ADD COLUMN downloadTreeUri TEXT")
            }
        }

        // v17 → v18 (§C28): offline_copy.displayName for friendly Drive names.
        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_copy ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * §C18 v10→v11 — adds the replay_gain pre-scan table.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS replay_gain (
                        mediaUri TEXT NOT NULL PRIMARY KEY,
                        trackGainDb REAL NOT NULL,
                        albumGainDb REAL NOT NULL,
                        albumKey TEXT NOT NULL,
                        scannedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * D12 v13→v14 — index bookmarks.createdAtMs (the
         * BookmarkDao.observeAll() ORDER BY column).
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bookmarks_createdAtMs ON bookmarks(createdAtMs)"
                )
            }
        }

        /**
         * §C17 A11.4 v14→v15 — adds the enrichment cache table so
         * repeat MusicBrainz / Discogs lookups skip the network.
         */
        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS enrichment_cache (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        provider TEXT NOT NULL,
                        title TEXT,
                        artist TEXT,
                        album TEXT,
                        year INTEGER,
                        genre TEXT,
                        artworkUrl TEXT,
                        fetchedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v15→v16 — Pinned albums. Two tables joined by FK CASCADE so
         * deleting an album drops its track snapshot atomically.
         */
        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pinned_albums (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        albumKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        artworkUri TEXT,
                        pinOrder INTEGER NOT NULL,
                        pinnedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pinned_albums_pinOrder ON pinned_albums(pinOrder)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pinned_albums_albumKey ON pinned_albums(albumKey)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pinned_album_tracks (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        albumId INTEGER NOT NULL,
                        mediaUri TEXT NOT NULL,
                        title TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        trackIndex INTEGER NOT NULL,
                        FOREIGN KEY (albumId) REFERENCES pinned_albums(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pinned_album_tracks_albumId ON pinned_album_tracks(albumId)")
            }
        }

        /**
         * §C10 v9→v10 — adds the podcast tables.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS podcast_shows (
                        feedUrl TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        artworkUrl TEXT,
                        description TEXT NOT NULL,
                        lastChecked INTEGER NOT NULL,
                        subscribedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS podcast_episodes (
                        guid TEXT NOT NULL PRIMARY KEY,
                        feedUrl TEXT NOT NULL,
                        title TEXT NOT NULL,
                        audioUrl TEXT NOT NULL,
                        durationS INTEGER NOT NULL,
                        publishedAt INTEGER NOT NULL,
                        isPlayed INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
