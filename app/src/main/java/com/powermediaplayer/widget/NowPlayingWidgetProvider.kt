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
import com.powermediaplayer.service.PlaybackService

/**
 * Phase 8 — home-screen now-playing widget.
 *
 * Buttons broadcast actions that THIS provider handles directly in
 * onReceive, NOT via TaskerReceiver — TaskerReceiver is gated by an
 * opt-in toggle, so wiring it through there made widget taps no-op
 * for users who hadn't enabled external automation. The widget is
 * an internal app surface and shouldn't require that gate.
 *
 * Title / artist / play-pause icon refresh on every widget update
 * (system tick, button press, or [refresh] called from the
 * playback listener). Real-time progress is intentionally omitted —
 * AppWidget RemoteViews don't support smooth-tickers without a
 * battery-eating wake source.
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, build(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAll(context)
            ACTION_PLAY_PAUSE -> {
                val player = PlaybackService.getExoPlayer()
                if (player != null) {
                    if (player.isPlaying) player.pause() else player.play()
                } else {
                    // No service alive yet — open the app so the user
                    // can pick a track. Tapping play/pause on a cold
                    // app shouldn't silently do nothing.
                    openMainActivity(context)
                }
                updateAll(context)
            }
            ACTION_PREV -> {
                val p = PlaybackService.getExoPlayer()
                if (p != null && p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                updateAll(context)
            }
            ACTION_NEXT -> {
                val p = PlaybackService.getExoPlayer()
                if (p != null && p.hasNextMediaItem()) p.seekToNextMediaItem()
                updateAll(context)
            }
        }
    }

    private fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(
            ComponentName(context, NowPlayingWidgetProvider::class.java)
        )
        for (id in ids) mgr.updateAppWidget(id, build(context))
    }

    private fun build(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

        val player = PlaybackService.getExoPlayer()
        val item = player?.currentMediaItem
        val title = item?.mediaMetadata?.title?.toString()?.ifBlank { null }
            ?: "Power Media Player"
        val artist = item?.mediaMetadata?.artist?.toString()?.ifBlank { null }
            ?: "Tap to open"
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)

        val isPlaying = player?.isPlaying == true
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        views.setOnClickPendingIntent(R.id.widget_top, piActivity(context, 0))
        views.setOnClickPendingIntent(
            R.id.widget_play_pause, piSelf(context, 1, ACTION_PLAY_PAUSE)
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev, piSelf(context, 2, ACTION_PREV)
        )
        views.setOnClickPendingIntent(
            R.id.widget_next, piSelf(context, 3, ACTION_NEXT)
        )
        return views
    }

    private fun openMainActivity(context: Context) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(open) }
    }

    private fun piActivity(context: Context, code: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, code, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun piSelf(context: Context, code: Int, action: String): PendingIntent {
        val intent = Intent(context, NowPlayingWidgetProvider::class.java)
            .setAction(action)
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.powermediaplayer.widget.REFRESH"
        const val ACTION_PLAY_PAUSE = "com.powermediaplayer.widget.PLAY_PAUSE"
        const val ACTION_PREV = "com.powermediaplayer.widget.PREV"
        const val ACTION_NEXT = "com.powermediaplayer.widget.NEXT"

        /** Fired by the playback layer when state changes. */
        fun refresh(context: Context) {
            context.sendBroadcast(
                Intent(context, NowPlayingWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
            )
        }
    }
}
