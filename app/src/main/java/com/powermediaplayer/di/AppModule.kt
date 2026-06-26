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
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-lifetime coroutine scope for playback-session side effects
 *  (PlaybackSessionCoordinator) — never cancelled by design. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module providing application-scoped singletons:
 * Room database, DAOs, DataStore, and PlaybackConnection.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Main.immediate
        )

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
            .fallbackToDestructiveMigrationFrom(false, 1, 2, 3, 4, 5, 6)
            // v7 → v8 (§C7): adds media_overrides table without
            // touching existing data. First non-destructive migration.
            // v8 → v9 (§C6): adds smart_playlists table.
            // v9 → v10 (§C10): adds podcast_shows + podcast_episodes.
            // v10 → v11 (§C18): adds replay_gain pre-scan table.
            .addMigrations(
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21
            )
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
    fun provideMediaOverrideDao(database: AppDatabase): com.powermediaplayer.data.db.dao.MediaOverrideDao {
        return database.mediaOverrideDao()
    }

    @Provides
    @Singleton
    fun provideMediaOverrideRepository(
        dao: com.powermediaplayer.data.db.dao.MediaOverrideDao,
        podcastDao: com.powermediaplayer.data.db.dao.PodcastDao
    ): com.powermediaplayer.data.repository.MediaOverrideRepository =
        com.powermediaplayer.data.repository.MediaOverrideRepository(dao, podcastDao)

    @Provides
    @Singleton
    fun provideSmartPlaylistDao(database: AppDatabase): com.powermediaplayer.data.db.dao.SmartPlaylistDao {
        return database.smartPlaylistDao()
    }

    @Provides
    @Singleton
    fun providePodcastDao(database: AppDatabase): com.powermediaplayer.data.db.dao.PodcastDao {
        return database.podcastDao()
    }

    @Provides
    @Singleton
    fun provideEnrichmentCacheDao(database: AppDatabase): com.powermediaplayer.data.db.dao.EnrichmentCacheDao {
        return database.enrichmentCacheDao()
    }

    @Provides
    @Singleton
    fun providePinnedAlbumDao(database: AppDatabase): com.powermediaplayer.data.db.dao.PinnedAlbumDao {
        return database.pinnedAlbumDao()
    }

    @Provides
    @Singleton
    fun provideReplayGainDao(database: AppDatabase): com.powermediaplayer.data.db.dao.ReplayGainDao {
        return database.replayGainDao()
    }

    @Provides
    @Singleton
    fun provideOfflineCopyDao(database: AppDatabase): com.powermediaplayer.data.db.dao.OfflineCopyDao {
        return database.offlineCopyDao()
    }

    @Provides
    @Singleton
    fun provideReplayGainScanner(
        @ApplicationContext context: Context,
        dao: com.powermediaplayer.data.db.dao.ReplayGainDao
    ): com.powermediaplayer.replaygain.ReplayGainScanner =
        com.powermediaplayer.replaygain.ReplayGainScanner(context, dao)

    @Provides
    @Singleton
    fun provideSubtitleAutoFetcher(
        @ApplicationContext context: Context,
        settingsDataStore: SettingsDataStore,
        credStore: com.powermediaplayer.subtitles.OpenSubsCredStore
    ): com.powermediaplayer.subtitles.SubtitleAutoFetcher =
        com.powermediaplayer.subtitles.SubtitleAutoFetcher(context, settingsDataStore, credStore)

    @Provides
    @Singleton
    fun provideOpenSubsCredStore(
        @ApplicationContext context: Context
    ): com.powermediaplayer.subtitles.OpenSubsCredStore =
        com.powermediaplayer.subtitles.OpenSubsCredStore(context)

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
