package com.powermediaplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.powermediaplayer.MainActivity
import com.powermediaplayer.R
import com.powermediaplayer.integration.TaskerReceiver
import com.powermediaplayer.service.PlaybackService

/**
 * Phase 8 — home-screen now-playing widget.
 *
 * Buttons fire the same intent actions [TaskerReceiver] already
 * handles, so we don't double-build playback wiring.
 *
 * Title / artist / play-pause icon refresh only on widget update
 * boundaries (system tick, button press, or [refresh] called from
 * the playback layer). Real-time progress is intentionally omitted —
 * AppWidget RemoteViews don't support smooth-tickers without a
 * battery-eating wake source, and Phase 8 is "lightweight glance",
 * not a mini-player.
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = build(context)
            manager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, NowPlayingWidgetProvider::class.java)
            )
            for (id in ids) mgr.updateAppWidget(id, build(context))
        }
    }

    private fun build(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

        // Pull title/artist directly from the running ExoPlayer if any.
        val player = PlaybackService.getExoPlayer()
        val item = player?.currentMediaItem
        val title = item?.mediaMetadata?.title?.toString()?.ifBlank { null }
            ?: "Power Media Player"
        val artist = item?.mediaMetadata?.artist?.toString()?.ifBlank { null }
            ?: "Tap to open"
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)

        // Play/pause icon reflects current isPlaying. We use a plain
        // play icon when nothing is loaded so first-launch users see a
        // sensible default.
        val isPlaying = player?.isPlaying == true
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        views.setOnClickPendingIntent(
            R.id.widget_top,
            piActivity(context, 0)
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            piTasker(context, 1, "com.powermediaplayer.action.PLAY_PAUSE")
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev,
            piTasker(context, 2, "com.powermediaplayer.action.SKIP_PREV")
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            piTasker(context, 3, "com.powermediaplayer.action.SKIP_NEXT")
        )
        return views
    }

    private fun piActivity(context: Context, code: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, code, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun piTasker(context: Context, code: Int, action: String): PendingIntent {
        val intent = Intent(action)
            .setComponent(ComponentName(context, TaskerReceiver::class.java))
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.powermediaplayer.widget.REFRESH"

        /** Trigger a widget refresh after a playback state change. */
        fun refresh(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_REFRESH).setPackage(context.packageName)
            )
        }
    }
}
