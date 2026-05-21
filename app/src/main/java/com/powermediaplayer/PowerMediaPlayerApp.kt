package com.powermediaplayer

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.diag.DiagLog
import com.powermediaplayer.podcast.PodcastSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

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
    @Inject lateinit var settingsDataStore: SettingsDataStore
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Initialise the opt-in file logger BEFORE installing the
        // uncaught exception handler so any crash during onCreate has
        // a place to land. Initial state is read synchronously (~1-5 ms
        // first-hit on DataStore); subsequent toggles are reactive.
        val startEnabled = runCatching {
            runBlocking { settingsDataStore.diagLogEnabled.first() }
        }.getOrDefault(false)
        DiagLog.init(this, startEnabled)
        DiagLog.lifecycle("PowerMediaPlayerApp.onCreate (process start)")
        // Live-track future toggle changes.
        appScope.launch {
            settingsDataStore.diagLogEnabled.collect { DiagLog.setEnabled(it) }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PowerMediaPlayer",
                "FATAL on thread ${thread.name}: ${throwable.javaClass.simpleName}: " +
                    "${throwable.message}", throwable)
            // Also write to the persistent log so a crash that nukes
            // the process leaves a breadcrumb the tester can pull.
            DiagLog.event(
                "FATAL",
                "thread=${thread.name} ex=${throwable.javaClass.simpleName} msg=${throwable.message} " +
                    "stack=${throwable.stackTraceToString().take(2000)}"
            )
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
