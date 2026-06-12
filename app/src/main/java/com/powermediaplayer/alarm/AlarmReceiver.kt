package com.powermediaplayer.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.powermediaplayer.R
import com.powermediaplayer.data.preferences.SettingsDataStore
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
        val snoozeCount = intent.getIntExtra(EXTRA_SNOOZE_COUNT, 0)
        if (alarmId < 0) return

        // goAsync: an alarm fire on a dead process must outlive onReceive
        // or the DataStore read races process teardown and the alarm is
        // silently dropped.
        val pending = goAsync()
        scope.launch {
            try {
            val alarm = settingsDataStore.scheduledAlarms.first()
                .firstOrNull { it.id == alarmId } ?: return@launch
            if (!alarm.enabled) return@launch

            // §C12 skip-next-N: decrement on each fire and skip if > 0.
            if (alarm.skipNextCount > 0 && snoozeCount == 0) {
                val updated = alarm.copy(skipNextCount = alarm.skipNextCount - 1)
                runCatching { settingsDataStore.upsertAlarm(updated) }
                if (alarm.days != 0) AlarmScheduler.schedule(context, updated)
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "AlarmReceiver SKIPPED id=${alarm.id} (skipNext=${alarm.skipNextCount}→${updated.skipNextCount})"
                )
                return@launch
            }

            // §C12 — Launch FullScreenAlarmActivity. The activity owns
            // the actual ringing (USAGE_ALARM MediaPlayer), volume ramp,
            // hold/wind-down, snooze, math/shake/tap stop, and vibration.
            val launchIntent = FullScreenAlarmActivity.intent(context, alarm.id, snoozeCount)
            postNotification(context, alarm, launchIntent)
            runCatching { context.startActivity(launchIntent) }

            // Reschedule next occurrence if recurring (days != 0).
            // Snooze fires never reschedule the master alarm — that
            // happens once when the original alarm fires.
            if (alarm.days != 0 && snoozeCount == 0) {
                AlarmScheduler.schedule(context, alarm)
            }

            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "AlarmReceiver fired id=${alarm.id} time=${alarm.timeLabel} " +
                    "days=${alarm.daysLabel} mediaUri=${alarm.mediaUri.ifBlank { "<resume>" }} " +
                    "snoozeCount=$snoozeCount"
            )
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(
        context: Context,
        alarm: AlarmRecord,
        fullScreenIntent: Intent
    ) {
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

        val fullScreenPi = android.app.PendingIntent.getActivity(
            context, alarm.id.toInt(), fullScreenIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Power Media Player alarm — ${alarm.timeLabel}")
            .setContentText(alarm.displayLabel.ifBlank { alarm.daysLabel })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .build()
        runCatching { nm.notify(alarm.id.toInt(), notif) }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_SNOOZE_COUNT = "snooze_count"
        const val ACTION_FIRE = "com.powermediaplayer.alarm.FIRE"
    }
}
