package com.powermediaplayer.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import com.powermediaplayer.MainActivity
import com.powermediaplayer.R
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
 * §C12 — fires when a scheduled alarm reaches its time. Resolves the
 * alarm record by id, plays its mediaUri (or resumes the last queued
 * track if mediaUri is empty), reschedules the next occurrence if
 * the alarm has days-of-week, and posts a high-priority alarm
 * notification so the user sees it on the lockscreen.
 *
 * Full-screen wake activity (USE_FULL_SCREEN_INTENT, alarm-clock-app
 * special access on Android 14+) is deferred — this v0.1 uses a
 * MAX-priority alarm-channel notification, which still wakes a
 * locked screen on most devices.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId < 0) return

        scope.launch {
            val alarm = settingsDataStore.scheduledAlarms.first()
                .firstOrNull { it.id == alarmId } ?: return@launch
            if (!alarm.enabled) return@launch

            // Start playback. If mediaUri is empty, fall back to whatever
            // the player has loaded (resume mode).
            val player = PlaybackService.getExoPlayer()
            if (alarm.mediaUri.isNotBlank()) {
                runCatching {
                    val item = MediaItem.Builder()
                        .setMediaId(alarm.mediaUri)
                        .setUri(android.net.Uri.parse(alarm.mediaUri))
                        .build()
                    player?.setMediaItems(listOf(item), 0, 0L)
                    player?.prepare()
                    player?.playWhenReady = true
                }
            } else if (player?.currentMediaItem != null) {
                player.play()
            } else {
                // No media at all — open the app so the user can pick one.
                runCatching {
                    val openApp = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(openApp)
                }
            }

            postNotification(context, alarm)
            // Reschedule next occurrence if recurring (days != 0).
            if (alarm.days != 0) AlarmScheduler.schedule(context, alarm)

            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "AlarmReceiver fired id=${alarm.id} time=${alarm.timeLabel} " +
                    "days=${alarm.daysLabel} mediaUri=${alarm.mediaUri.ifBlank { "<resume>" }}"
            )
        }
    }

    private fun postNotification(context: Context, alarm: AlarmRecord) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Alarm channel — MAX importance so it bypasses DND/silent.
        val channelId = "pmp_alarm_v1"
        val ch = NotificationChannel(
            channelId, "Wake-up alarms", NotificationManager.IMPORTANCE_MAX
        ).apply {
            description = "Power Media Player wake-up alarms"
            setBypassDnd(true)
        }
        nm.createNotificationChannel(ch)

        val tap = android.app.PendingIntent.getActivity(
            context, alarm.id.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Power Media Player alarm — ${alarm.timeLabel}")
            .setContentText(alarm.daysLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { nm.notify(alarm.id.toInt(), notif) }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val ACTION_FIRE = "com.powermediaplayer.alarm.FIRE"
    }
}
