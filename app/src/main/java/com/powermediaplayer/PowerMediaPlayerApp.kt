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
class PowerMediaPlayerApp : Application(), Configuration.Provider,
    coil3.SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsDataStore: SettingsDataStore
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * Per-track album art: register [com.powermediaplayer.util.LocalTrackArt]
     * Fetcher + Keyer so LOCAL rows resolve the file's OWN embedded picture
     * (then a legitimate album-art fallback), never a borrowed album-level
     * cover. Cloud http covers keep Coil's stock fetchers.
     */
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components {
                add(com.powermediaplayer.util.LocalTrackArtFetcher.ArtKeyer())
                add(com.powermediaplayer.util.LocalTrackArtFetcher.Factory(context))
                // #12 — decode a local video's first frame as a row thumbnail.
                add(coil3.video.VideoFrameDecoder.Factory())
                // S6 — downloaded media rows: embedded artwork first, else a video
                // frame, else fall through to the file icon (MediaThumbnailRequest).
                add(com.powermediaplayer.util.MediaThumbnailFetcher.Key())
                add(com.powermediaplayer.util.MediaThumbnailFetcher.Factory(context))
            }
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
        // A4 — guard the WorkManager `no_backup/androidx.work.workdb` crash
        // (SQLITE_CANTOPEN: "no_backup doesn't exist", logcat 06-17). The dir is
        // normally framework-created, but was missing on one device; touching
        // noBackupFilesDir creates it, so WorkManager's WorkDatabase open (which
        // runs on WorkManager's OWN thread, outside the enqueue runCatching
        // below) can't fail with ENOENT.
        runCatching { applicationContext.noBackupFilesDir }
        // §C10 / F6 fix — kick off periodic feed refresh on a worker
        // thread so the synchronous WorkManager.getInstance() call
        // doesn't sit on Application.onCreate's main-thread budget on
        // slower devices.
        Thread {
            runCatching { PodcastSyncWorker.enqueueIfNeeded(this) }
        }.start()
    }
}
