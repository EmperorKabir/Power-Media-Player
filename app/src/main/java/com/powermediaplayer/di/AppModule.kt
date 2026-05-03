package com.powermediaplayer.di

import android.content.Context
import androidx.room.Room
import com.powermediaplayer.data.db.AppDatabase
import com.powermediaplayer.data.db.dao.EqualizerPresetDao
import com.powermediaplayer.data.db.dao.FavoriteDao
import com.powermediaplayer.data.db.dao.PlaybackStateDao
import com.powermediaplayer.cloud.GoogleDriveProvider
import com.powermediaplayer.cloud.SpotifyProvider
import com.powermediaplayer.cloud.SpotifyTokenStore
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
            // v2 → v3 added the `bookmarks` table. No need to keep
            // schema-less migration; destroy on bump.
            .fallbackToDestructiveMigration(true)
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
    fun provideBookmarkDao(database: AppDatabase): com.powermediaplayer.data.db.dao.BookmarkDao {
        return database.bookmarkDao()
    }

    @Provides
    @Singleton
    fun providePlaybackHistoryDao(database: AppDatabase): com.powermediaplayer.data.db.dao.PlaybackHistoryDao {
        return database.playbackHistoryDao()
    }

    @Provides
    @Singleton
    fun provideHistoryFavouriteDao(database: AppDatabase): com.powermediaplayer.data.db.dao.HistoryFavouriteDao {
        return database.historyFavouriteDao()
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

    @Provides
    @Singleton
    fun provideSpotifyTokenStore(
        @ApplicationContext context: Context
    ): SpotifyTokenStore = SpotifyTokenStore(context)

    @Provides
    @Singleton
    fun provideSpotifyProvider(
        @ApplicationContext context: Context,
        tokenStore: SpotifyTokenStore
    ): SpotifyProvider = SpotifyProvider(context, tokenStore)

    @Provides
    @Singleton
    fun provideGoogleDriveProvider(
        @ApplicationContext context: Context
    ): GoogleDriveProvider = GoogleDriveProvider(context)
}
