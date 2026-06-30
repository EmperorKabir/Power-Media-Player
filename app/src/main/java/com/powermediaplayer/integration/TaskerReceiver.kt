package com.powermediaplayer.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.service.PlaybackService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §C3 — single BroadcastReceiver that handles every external-control
 * action so a user automating with Tasker / Macrodroid / shell intents
 * has a uniform surface. Gated by the SettingsDataStore.taskerIntents
 * Enabled toggle (default OFF for security — short-circuits and
 * ignores incoming intents until the user explicitly opts in).
 *
 * Documented action strings (each handled here):
 *   com.powermediaplayer.action.PLAY
 *   com.powermediaplayer.action.PAUSE
 *   com.powermediaplayer.action.PLAY_PAUSE
 *   com.powermediaplayer.action.SKIP_NEXT
 *   com.powermediaplayer.action.SKIP_PREV
 *   com.powermediaplayer.action.SKIP_BACK_30   // skip back 30 s
 *   com.powermediaplayer.action.SKIP_FORWARD_30
 *   com.powermediaplayer.action.SEEK_TO        // requires `position_ms` long extra
 */
@AndroidEntryPoint
class TaskerReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!action.startsWith("com.powermediaplayer.action.")) return
        // goAsync: onReceive returns before the coroutine runs; without a
        // PendingResult the process can be killed mid-command on a cold
        // receiver wake (automation intents silently dropped).
        val pending = goAsync()
        scope.launch {
            try {
            val enabled = settingsDataStore.taskerIntentsEnabled.first()
            if (!enabled) {
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "TaskerReceiver ignored '$action', toggle is OFF"
                )
                return@launch
            }
            val player = PlaybackService.getExoPlayer() ?: run {
                com.powermediaplayer.util.Diag.w(
                    "PMP_DIAG",
                    "TaskerReceiver no ExoPlayer for '$action'"
                )
                return@launch
            }
            when (action) {
                "com.powermediaplayer.action.PLAY" -> player.play()
                "com.powermediaplayer.action.PAUSE" -> player.pause()
                "com.powermediaplayer.action.PLAY_PAUSE" ->
                    if (player.isPlaying) player.pause() else player.play()
                "com.powermediaplayer.action.SKIP_NEXT" -> {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                }
                "com.powermediaplayer.action.SKIP_PREV" -> {
                    if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                }
                "com.powermediaplayer.action.SKIP_BACK_30" -> {
                    player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0L))
                }
                "com.powermediaplayer.action.SKIP_FORWARD_30" -> {
                    val dur = player.duration.coerceAtLeast(0L)
                    val next = player.currentPosition + 30_000L
                    player.seekTo(if (dur > 0) next.coerceAtMost(dur - 1_000L) else next)
                }
                "com.powermediaplayer.action.SEEK_TO" -> {
                    val pos = intent.getLongExtra("position_ms", -1L)
                    if (pos >= 0L) player.seekTo(pos)
                }
                else -> com.powermediaplayer.util.Diag.w(
                    "PMP_DIAG", "TaskerReceiver unknown action='$action'"
                )
            }
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "TaskerReceiver handled '$action'")
            } finally {
                pending.finish()
            }
        }
    }
}
