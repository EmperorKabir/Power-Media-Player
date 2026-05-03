package com.powermediaplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.powermediaplayer.data.db.dao.BookmarkDao
import com.powermediaplayer.data.db.dao.EqualizerPresetDao
import com.powermediaplayer.data.db.dao.FavoriteDao
import com.powermediaplayer.data.db.dao.HistoryFavouriteDao
import com.powermediaplayer.data.db.dao.PlaybackHistoryDao
import com.powermediaplayer.data.db.dao.PlaybackStateDao
import com.powermediaplayer.data.db.entity.BookmarkEntity
import com.powermediaplayer.data.db.entity.EqualizerPresetEntity
import com.powermediaplayer.data.db.entity.FavoriteEntity
import com.powermediaplayer.data.db.entity.HistoryFavouriteEntity
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
        HistoryFavouriteEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun equalizerPresetDao(): EqualizerPresetDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun historyFavouriteDao(): HistoryFavouriteDao

    companion object {
        const val DATABASE_NAME = "power_media_player.db"
    }
}
