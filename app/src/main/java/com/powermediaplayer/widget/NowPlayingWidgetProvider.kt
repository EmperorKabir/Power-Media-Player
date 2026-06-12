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
        if (ids.isEmpty()) return
        // Build ONCE — the old per-id build re-decoded the album art for
        // every placed widget instance (audit 3.10).
        val views = build(context)
        for (id in ids) mgr.updateAppWidget(id, views)
    }

    private fun build(context: Context): RemoteViews {
        // §C20 — three layout variants chosen by widget host based on
        // physical size (Android 12+: RemoteViews(Map<SizeF, RV>);
        // older: single Large layout). Compact = 1×1, Wide = 4×1,
        // Large = 4×2.
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.widget.RemoteViews(
                mapOf(
                    android.util.SizeF(60f, 60f) to buildVariant(
                        context, R.layout.widget_now_playing_compact, hasText = false, hasTransport = false
                    ),
                    android.util.SizeF(180f, 60f) to buildVariant(
                        context, R.layout.widget_now_playing_wide, hasText = true, hasTransport = true
                    ),
                    android.util.SizeF(180f, 120f) to buildVariant(
                        context, R.layout.widget_now_playing, hasText = true, hasTransport = true
                    )
                )
            )
        } else {
            buildVariant(context, R.layout.widget_now_playing, hasText = true, hasTransport = true)
        }
    }

    private fun buildVariant(
        context: Context,
        layoutId: Int,
        hasText: Boolean,
        hasTransport: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        val player = PlaybackService.getExoPlayer()
        val item = player?.currentMediaItem
        if (hasText) {
            val title = item?.mediaMetadata?.title?.toString()?.ifBlank { null }
                ?: "Power Media Player"
            val artist = item?.mediaMetadata?.artist?.toString()?.ifBlank { null }
                ?: "Tap to open"
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            // Album art: try embedded artworkData (mp3/m4a tag), fall
            // back to the layered placeholder drawable.
            val art = item?.mediaMetadata?.artworkData
            if (art != null && art.isNotEmpty()) {
                val bmp = scaledArt(item.mediaId.orEmpty(), art)
                if (bmp != null) {
                    views.setImageViewBitmap(R.id.widget_art, bmp)
                } else {
                    views.setImageViewResource(
                        R.id.widget_art, R.drawable.widget_art_placeholder
                    )
                }
            } else {
                views.setImageViewResource(
                    R.id.widget_art, R.drawable.widget_art_placeholder
                )
            }
        }
        val isPlaying = player?.isPlaying == true
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        // Click intents — every variant exposes play_pause; only
        // wide / large get prev / next; large additionally has the
        // top-row tap-to-open.
        views.setOnClickPendingIntent(
            R.id.widget_play_pause, piSelf(context, 1, ACTION_PLAY_PAUSE)
        )
        if (hasTransport) {
            views.setOnClickPendingIntent(
                R.id.widget_prev, piSelf(context, 2, ACTION_PREV)
            )
            views.setOnClickPendingIntent(
                R.id.widget_next, piSelf(context, 3, ACTION_NEXT)
            )
        }
        if (layoutId == R.layout.widget_now_playing) {
            views.setOnClickPendingIntent(R.id.widget_top, piActivity(context, 0))
        }
        return views
    }

    private fun openMainActivity(context: Context) {
        val open = openPlayerIntent(context)
        runCatching { context.startActivity(open) }
    }

    private fun piActivity(context: Context, code: Int): PendingIntent {
        return PendingIntent.getActivity(
            context, code, openPlayerIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * §C20 — widget tap deep-links to the Player tab. MainActivity
     * reads [EXTRA_OPEN_TAB] in onNewIntent and routes the NavHost
     * accordingly; without the extra it lands on the user's last tab.
     */
    private fun openPlayerIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TAB, TAB_PLAYER)

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
        const val EXTRA_OPEN_TAB = "com.powermediaplayer.widget.OPEN_TAB"
        const val TAB_PLAYER = "player"

        /** Fired by the playback layer when state changes. */
        fun refresh(context: Context) {
            context.sendBroadcast(
                Intent(context, NowPlayingWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
            )
        }

        // One scaled decode per track (audit 3.10): the widget cell is
        // ~64dp; full-res embedded covers (often 1000px+) were decoded
        // on the main thread per refresh and shipped whole across the
        // RemoteViews binder (TransactionTooLarge risk).
        @Volatile private var artCacheKey: String? = null
        @Volatile private var artCacheBmp: android.graphics.Bitmap? = null

        internal fun scaledArt(mediaId: String, art: ByteArray): android.graphics.Bitmap? {
            if (mediaId.isNotEmpty() && mediaId == artCacheKey) return artCacheBmp
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
            val target = 192 // px - 64dp cell at up to 3x density
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            return runCatching {
                android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, opts)
            }.getOrNull().also {
                if (mediaId.isNotEmpty()) { artCacheKey = mediaId; artCacheBmp = it }
            }
        }
    }
}
