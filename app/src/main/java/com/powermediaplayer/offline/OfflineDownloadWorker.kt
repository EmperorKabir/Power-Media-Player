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
        val r = offlineMediaManager.download(uri, title)
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
    private const val PROGRESS_BASE = 47000
    private const val DONE_BASE = 48000

    private fun notifId(base: Int, uri: String): Int = base + (uri.hashCode() and 0x0FFF)

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Offline downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Saving Drive files for offline use" }
            )
        }
    }

    fun progressForeground(context: Context, uri: String, title: String): ForegroundInfo {
        ensureChannel(context)
        val n: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Saving offline")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val id = notifId(PROGRESS_BASE, uri)
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, n)
    }

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
                .build()
        )
    }
}
