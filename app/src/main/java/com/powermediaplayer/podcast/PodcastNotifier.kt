package com.powermediaplayer.podcast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.powermediaplayer.R
import com.powermediaplayer.data.db.entity.PodcastShowEntity

/**
 * New-episode notifications (2026-07-09, investigation item 3): the per-show
 * "Notify on new episode" toggle previously persisted a flag that nothing
 * consumed. [PodcastSyncWorker] now calls [notifyNewEpisodes] when a sync
 * inserts genuinely fresh episodes for a flagged show.
 *
 * A dedicated channel means the alert sound/vibration is user-choosable in
 * system settings (App notifications → "New podcast episodes") — the standard
 * Android home for per-channel sound, which is why no in-app sound picker is
 * needed. One notification per show per sync, tagged by feed so a later sync
 * for the same show replaces rather than stacks.
 */
object PodcastNotifier {

    private const val CHANNEL_ID = "new_episodes"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "New podcast episodes",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when a subscribed podcast publishes new episodes"
            }
        )
    }

    fun notifyNewEpisodes(
        context: Context,
        show: PodcastShowEntity,
        count: Int,
        newestTitle: String?
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            com.powermediaplayer.diag.DiagLog.event(
                "PODCAST",
                "new-episode notification suppressed (permission off) show=${show.title} count=$count"
            )
            return
        }
        ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pi = launch?.let {
            PendingIntent.getActivity(
                context, show.feedUrl.hashCode(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val text = if (count == 1) (newestTitle ?: "1 new episode")
            else "$count new episodes" + (newestTitle?.let { ", latest: $it" } ?: "")
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(show.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(show.feedUrl.hashCode(), n)
            com.powermediaplayer.diag.DiagLog.event(
                "PODCAST",
                "new-episode notification posted show=${show.title} count=$count"
            )
        }
    }
}
