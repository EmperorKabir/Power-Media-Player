package com.powermediaplayer.di

import android.content.Context
import androidx.room.Room
import com.powermediaplayer.data.db.AppDatabase
import com.powermediaplayer.data.db.dao.EqualizerPresetDao
import com.powermediaplayer.data.db.dao.FavoriteDao
import com.powermediaplayer.data.db.dao.PlaybackStateDao
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.service.PlaybackConnection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-scoped singletons:
 * Room database, DAOs, DataStore, and PlaybackConnection.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            // v1 → v2 added the `favorite` table; no existing user data needs to
            // be preserved across this bump, so destructive migration is fine.
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideEqualizerPresetDao(database: AppDatabase): EqualizerPresetDao {
        return database.equalizerPresetDao()
    }

    @Provides
    @Singleton
    fun providePlaybackStateDao(database: AppDatabase): PlaybackStateDao {
        return database.playbackStateDao()
    }

    @Provides
    @Singleton
    fun provideFavoriteDao(database: AppDatabase): FavoriteDao {
        return database.favoriteDao()
    }

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context
    ): SettingsDataStore {
        return SettingsDataStore(context)
    }

    @Provides
    @Singleton
    fun providePlaybackConnection(
        @ApplicationContext context: Context
    ): PlaybackConnection {
        return PlaybackConnection(context)
    }
}
