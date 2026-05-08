package com.powermediaplayer

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.powermediaplayer.podcast.PodcastSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class annotated for Hilt dependency injection.
 * This is the entry point for the Hilt component hierarchy.
 *
 * Also installs an uncaught exception handler that LOGS the full stack
 * trace before delegating to the platform default — without this, "app
 * keeps crashing" / "Clear cache?" dialogs leave us nothing to debug.
 * Filter logcat: `adb logcat -s PowerMediaPlayer:V *:S`.
 *
 * §C10 — implements [Configuration.Provider] so WorkManager picks up
 * Hilt's [HiltWorkerFactory], allowing @HiltWorker classes (e.g.
 * [PodcastSyncWorker]) to take constructor-injected dependencies.
 */
@HiltAndroidApp
class PowerMediaPlayerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PowerMediaPlayer",
                "FATAL on thread ${thread.name}: ${throwable.javaClass.simpleName}: " +
                    "${throwable.message}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        // §C10 / F6 fix — kick off periodic feed refresh on a worker
        // thread so the synchronous WorkManager.getInstance() call
        // doesn't sit on Application.onCreate's main-thread budget on
        // slower devices.
        Thread {
            runCatching { PodcastSyncWorker.enqueueIfNeeded(this) }
        }.start()
    }
}
