package com.powermediaplayer.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.launch

/**
 * P4 — run a "download for offline use" as a WorkManager FOREGROUND worker so it
 * survives leaving the tab, backgrounding the app, and process death (WorkManager
 * reschedules), and shows a persistent progress notification + a completion/failure
 * notification. It REUSES [OfflineMediaManager.download] verbatim — only the execution
 * context changes (was a screen-scoped viewModelScope that the OS cancelled on tab-pop
 * / background, silently dropping the download and orphaning its cacheDir staging file).
 */
@HiltWorker
class OfflineDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val offlineMediaManager: OfflineMediaManager
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        OfflineDownloadNotifier.progressForeground(
            appContext, inputData.getString(KEY_URI).orEmpty(),
            inputData.getString(KEY_TITLE) ?: "file"
        )

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val uri = inputData.getString(KEY_URI) ?: return androidx.work.ListenableWorker.Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "file"
        runCatching { setForeground(getForegroundInfo()) }
        // Mirror the live byte progress onto the foreground notification so the
        // user can see HOW FAR the download got, even with the app closed — was
        // an indeterminate spinner that gave no "part downloaded" feedback.
        val r = kotlinx.coroutines.coroutineScope {
            val progressId = runCatching { offlineMediaManager.progressIdFor(uri) }.getOrNull()
            val progressTap = OfflineDownloadNotifier.openTabIntent(
                appContext, uri, com.powermediaplayer.widget.NowPlayingWidgetProvider.TAB_CLOUD
            )
            val pj = if (progressId != null) launch {
                var lastPct = -1
                com.powermediaplayer.util.DownloadProgressBus.progressFor(progressId).collect { p ->
                    if (p != null && p.total > 0L) {
                        val pct = (p.fraction * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            runCatching {
                                setForeground(
                                    OfflineDownloadNotifier.progressForeground(appContext, uri, title, pct, progressTap)
                                )
                            }
                        }
                    }
                }
            } else null
            val result = offlineMediaManager.download(uri, title)
            pj?.cancel()
            result
        }
        return if (r.isSuccess) {
            OfflineDownloadNotifier.completed(appContext, uri, title)
            androidx.work.ListenableWorker.Result.success()
        } else {
            OfflineDownloadNotifier.failed(appContext, uri, title, r.exceptionOrNull()?.message)
            androidx.work.ListenableWorker.Result.failure()
        }
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_TITLE = "title"

        /** Enqueue a foreground download, deduped by uri (a re-tap while one is in
         *  flight keeps the running work). */
        fun enqueue(context: Context, uri: String, title: String) {
            val work = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setInputData(workDataOf(KEY_URI to uri, KEY_TITLE to title))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("offline-dl:$uri", ExistingWorkPolicy.KEEP, work)
        }
    }
}

/** Notifications for [OfflineDownloadWorker]: a foreground progress notification while
 *  downloading (visible outside the app) + a terminal saved/failed notification. */
object OfflineDownloadNotifier {
    private const val CHANNEL = "offline_downloads"
    // Bases 100k apart with a 16-bit slot mask so the progress range
    // (100000..165535) and the done range (200000..265535) can NEVER overlap, and
    // concurrent same-type downloads collide only 1/65536 vs the old 1/4096 (audit P4-3).
    private const val PROGRESS_BASE = 100000
    private const val DONE_BASE = 200000

    private fun notifId(base: Int, uri: String): Int = base + (uri.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Offline downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Saving Drive files for offline use" }
            )
        }
    }

    /** [contentIntent]: the worker builds the tap action once per download and passes it in
     *  (AU3 eff #5: one ActivityManager round-trip per 1 % tick otherwise); null builds one. */
    fun progressForeground(
        context: Context, uri: String, title: String, pct: Int? = null,
        contentIntent: android.app.PendingIntent? = null
    ): ForegroundInfo {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Saving offline")
            .setContentText(if (pct != null) "$title  ·  $pct%" else title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(
                contentIntent ?: openTabIntent(context, uri, com.powermediaplayer.widget.NowPlayingWidgetProvider.TAB_CLOUD)
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Determinate bar once we know the size, so the user sees HOW FAR the
        // download got (survives leaving/closing the app); indeterminate until then.
        if (pct != null) builder.setProgress(100, pct.coerceIn(0, 100), false)
        else builder.setProgress(0, 0, true)
        val n: Notification = builder.build()
        val id = notifId(PROGRESS_BASE, uri)
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, n)
    }

    /**
     * 2026-10-30 (user video, issue 1): these notifications had NO content intent, so tapping
     * "Saved offline" did nothing. Each one now opens its own section: a finished download opens
     * the Library (Audio) tab, whose "Downloaded" section lists the saved file; a download in
     * progress or a failed one opens the Cloud tab, where the Drive row shows the progress and
     * offers the retry. Same deep-link extra the widget uses; NotificationTargetsTest asserts it.
     */
    fun openTabIntent(context: Context, uri: String, tab: String): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(
            context,
            notifId(DONE_BASE, uri),
            android.content.Intent(context, com.powermediaplayer.MainActivity::class.java)
                // Per-target ACTION: the progress (Cloud) and completed (Library) records for
                // one uri no longer overwrite each other (AU3 #6). No CLEAR_TOP (AU3 #2).
                .setAction(com.powermediaplayer.widget.NowPlayingWidgetProvider.actionFor(tab))
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(com.powermediaplayer.widget.NowPlayingWidgetProvider.EXTRA_OPEN_TAB, tab),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

    fun completed(context: Context, uri: String, title: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            notifId(DONE_BASE, uri),
            NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle("Saved offline")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(openTabIntent(context, uri, com.powermediaplayer.widget.NowPlayingWidgetProvider.TAB_LIBRARY))
                .build()
        )
    }

    fun failed(context: Context, uri: String, title: String, reason: String?) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            notifId(DONE_BASE, uri),
            NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle("Download failed")
                .setContentText(reason?.let { "$title — $it" } ?: title)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(openTabIntent(context, uri, com.powermediaplayer.widget.NowPlayingWidgetProvider.TAB_CLOUD))
                .build()
        )
    }
}
