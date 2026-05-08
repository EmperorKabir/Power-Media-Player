package com.powermediaplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.powermediaplayer.data.db.dao.BookmarkDao
import com.powermediaplayer.data.db.dao.EqualizerPresetDao
import com.powermediaplayer.data.db.dao.FavoriteDao
import com.powermediaplayer.data.db.dao.FavouriteBookmarkDao
import com.powermediaplayer.data.db.dao.HistoryBookmarkDao
import com.powermediaplayer.data.db.dao.HistoryFavouriteDao
import com.powermediaplayer.data.db.dao.MediaOverrideDao
import com.powermediaplayer.data.db.dao.PlaybackHistoryDao
import com.powermediaplayer.data.db.dao.PlaybackStateDao
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.dao.ReplayGainDao
import com.powermediaplayer.data.db.dao.SmartPlaylistDao
import com.powermediaplayer.data.db.entity.BookmarkEntity
import com.powermediaplayer.data.db.entity.EqualizerPresetEntity
import com.powermediaplayer.data.db.entity.FavoriteEntity
import com.powermediaplayer.data.db.entity.FavouriteBookmarkEntity
import com.powermediaplayer.data.db.entity.HistoryBookmarkEntity
import com.powermediaplayer.data.db.entity.HistoryFavouriteEntity
import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import com.powermediaplayer.data.db.entity.PlaybackHistoryEntity
import com.powermediaplayer.data.db.entity.PlaybackStateEntity
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.data.db.entity.ReplayGainEntity
import com.powermediaplayer.data.db.entity.SmartPlaylistEntity

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
        ReplayGainEntity::class
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
    version = 11,
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
