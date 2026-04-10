package com.powermediaplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.powermediaplayer.data.db.dao.EqualizerPresetDao
import com.powermediaplayer.data.db.dao.PlaybackStateDao
import com.powermediaplayer.data.db.entity.EqualizerPresetEntity
import com.powermediaplayer.data.db.entity.PlaybackStateEntity

/**
 * Main Room database for Power Media Player.
 * Contains tables for equalizer presets and playback state.
 */
@Database(
    entities = [
        EqualizerPresetEntity::class,
        PlaybackStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun equalizerPresetDao(): EqualizerPresetDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        const val DATABASE_NAME = "power_media_player.db"
    }
}
