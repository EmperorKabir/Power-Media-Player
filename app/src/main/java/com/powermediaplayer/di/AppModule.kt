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
            // Beta-tier users (v1.x .. v6.x) get a one-time destructive
            // migration into v1.0's v7 schema — the master-plan policy
            // ("v1.0 = fresh start; data does not migrate from beta")
            // has to be explicitly authorised here, otherwise Room
            // throws IllegalStateException on launch and the app
            // force-closes for every beta tester upgrading to v1.0.
            // Future schema bumps from v7 onwards MUST ship with a
            // proper Migration object — see docs/MIGRATION_INSTRUCTIONS.md.
            .fallbackToDestructiveMigrationFrom(false, 1, 2, 3, 4, 5, 6)
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
    fun provideHistoryBookmarkDao(database: AppDatabase): com.powermediaplayer.data.db.dao.HistoryBookmarkDao {
        return database.historyBookmarkDao()
    }

    @Provides
    @Singleton
    fun provideFavouriteBookmarkDao(database: AppDatabase): com.powermediaplayer.data.db.dao.FavouriteBookmarkDao {
        return database.favouriteBookmarkDao()
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
        @ApplicationContext context: Context,
        settingsDataStore: SettingsDataStore
    ): GoogleDriveProvider = GoogleDriveProvider(context, settingsDataStore)

    @Provides
    @Singleton
    fun provideDriveOAuthProvider(
        @ApplicationContext context: Context,
        settingsDataStore: SettingsDataStore
    ): com.powermediaplayer.cloud.DriveOAuthProvider =
        com.powermediaplayer.cloud.DriveOAuthProvider(context, settingsDataStore)
}
