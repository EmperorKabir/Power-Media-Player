package com.powermediaplayer.widget

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.powermediaplayer.service.PlaybackService

/**
 * §C1 follow-up — Quick Settings tile for skip-forward-15-seconds.
 */
class SkipForward15Tile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.run {
            label = "+15 s"
            icon = Icon.createWithResource(applicationContext, android.R.drawable.ic_media_ff)
            state = if (PlaybackService.getExoPlayer()?.currentMediaItem != null)
                Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val p = PlaybackService.getExoPlayer() ?: return
        val dur = p.duration.coerceAtLeast(0L)
        val target = (p.currentPosition + 15_000L).coerceAtMost(if (dur > 0) dur - 1_000L else Long.MAX_VALUE)
        p.seekTo(target)
    }
}
