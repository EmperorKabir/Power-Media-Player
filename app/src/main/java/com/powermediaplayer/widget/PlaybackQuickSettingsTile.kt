package com.powermediaplayer.widget

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.powermediaplayer.R
import com.powermediaplayer.service.PlaybackService

/**
 * §C1 — Quick Settings tile for play/pause. User adds the tile from
 * the system QS edit panel; once added, tapping the tile toggles
 * playback without unlocking the device.
 *
 * Skip-back / skip-forward variants will land as separate tiles in a
 * follow-up — the system Quick Settings UI groups multiple tiles, so
 * a 3-tile suite is straightforward. Phase 1 ships just play/pause.
 *
 * Manifest entry handles binding via BIND_QUICK_SETTINGS_TILE permission.
 */
class PlaybackQuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val player = PlaybackService.getExoPlayer() ?: run {
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "Power Media Player"
                updateTile()
            }
            return
        }
        if (player.isPlaying) player.pause() else player.play()
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val player = PlaybackService.getExoPlayer()
        val playing = player?.isPlaying == true
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (playing) "Pause" else "Play"
        tile.icon = Icon.createWithResource(
            applicationContext,
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        tile.updateTile()
    }
}
