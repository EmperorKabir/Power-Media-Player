package com.powermediaplayer.widget

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.powermediaplayer.service.PlaybackService

/**
 * §C1 follow-up — Quick Settings tile for skip-back-15-seconds. The
 * user adds it from QS edit alongside the play/pause + skip-forward
 * tiles; tap fires a 15 s rewind on the live ExoPlayer without
 * unlocking the device.
 */
class SkipBack15Tile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.run {
            label = "−15 s"
            icon = Icon.createWithResource(applicationContext, android.R.drawable.ic_media_rew)
            state = if (PlaybackService.getExoPlayer()?.currentMediaItem != null)
                Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val p = PlaybackService.getExoPlayer() ?: return
        val target = (p.currentPosition - 15_000L).coerceAtLeast(0L)
        p.seekTo(target)
    }
}
