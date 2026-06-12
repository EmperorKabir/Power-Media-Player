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
        // Audit 7.2 — log-only StrictMode in debug builds so main-thread
        // IO and leaked closables surface at commit time instead of in
        // device profiles. penaltyLog only: the bounded runBlocking seeds
        // in PlaybackService.onCreate are deliberate and must not crash.
        if (com.powermediaplayer.BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyLog().build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects().detectActivityLeaks()
                    .penaltyLog().build()
            )
        }
        // Initialise the opt-in file logger BEFORE installing the
        // uncaught exception handler so any crash during onCreate has
        // a place to land. The enabled flag arrives asynchronously —
        // the first-ever DataStore read is a disk open+parse and has no
        // business on the cold-start critical path (audit 2.3). Events
        // logged before the flag lands sit in DiagLog's pre-enable
        // buffer and flush into the file when (if) it flips on.
        DiagLog.init(this, initiallyEnabled = false)
        DiagLog.lifecycle("PowerMediaPlayerApp.onCreate (process start)")
        // Shared OkHttp cache home (audit 5.3) — must land before any
        // client class initialises.
        com.powermediaplayer.util.SharedHttp.installCacheDir(cacheDir)
        // Live-track the toggle; the first emission delivers the
        // persisted cold-start value.
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
            // fatalSync appends synchronously — the async writer may
            // never drain again on this process.
            DiagLog.fatalSync(
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
