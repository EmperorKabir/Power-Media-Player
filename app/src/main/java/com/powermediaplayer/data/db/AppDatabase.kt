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
import com.powermediaplayer.data.db.entity.BookmarkEntity
import com.powermediaplayer.data.db.entity.EqualizerPresetEntity
import com.powermediaplayer.data.db.entity.FavoriteEntity
import com.powermediaplayer.data.db.entity.FavouriteBookmarkEntity
import com.powermediaplayer.data.db.entity.HistoryBookmarkEntity
import com.powermediaplayer.data.db.entity.HistoryFavouriteEntity
import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import com.powermediaplayer.data.db.entity.PlaybackHistoryEntity
import com.powermediaplayer.data.db.entity.PlaybackStateEntity

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
        MediaOverrideEntity::class
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
    version = 8,
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
    }
}
