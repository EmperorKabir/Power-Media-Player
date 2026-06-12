package com.powermediaplayer.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.powermediaplayer.service.PlaybackService

/**
 * Audit 6.12 — the PiP window had no controls (Media3 1.6 does not
 * bridge MediaSession into PiP actions automatically). First-party
 * surface, so unlike TaskerReceiver there is no opt-in gate; not
 * exported — only our own PendingIntents can reach it.
 */
class PipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val player = PlaybackService.getExoPlayer() ?: return
        when (intent.action) {
            ACTION_PLAY_PAUSE ->
                if (player.isPlaying) player.pause() else player.play()
            ACTION_FFWD15 -> {
                val dur = player.duration.coerceAtLeast(0L)
                val next = player.currentPosition + 15_000L
                player.seekTo(if (dur > 0) next.coerceAtMost(dur - 1_000L) else next)
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.powermediaplayer.pip.PLAY_PAUSE"
        const val ACTION_FFWD15 = "com.powermediaplayer.pip.FFWD15"
    }
}
