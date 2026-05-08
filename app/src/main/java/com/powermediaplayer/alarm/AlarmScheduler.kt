package com.powermediaplayer.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * §C12 — wires AlarmRecord → AlarmManager.setAlarmClock.
 *
 * Uses setAlarmClock (not setExact) so the alarm survives Doze and the
 * system shows the alarm icon in the status bar, signalling clearly to
 * the user that an alarm is queued. Requires SCHEDULE_EXACT_ALARM /
 * USE_EXACT_ALARM permission (auto-granted to alarm-clock-class apps
 * on Android 13+ via USE_EXACT_ALARM in the manifest).
 *
 * For recurring alarms (days != 0), AlarmReceiver re-schedules the next
 * matching weekday after each fire — AlarmManager doesn't natively
 * support multi-day recurrence.
 */
object AlarmScheduler {

    fun schedule(context: Context, alarm: AlarmRecord) {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = computeNextTriggerMs(alarm)
        if (triggerAt <= 0L) return

        val pi = pendingIntent(context, alarm.id, flags = PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return
        val info = AlarmManager.AlarmClockInfo(triggerAt, /* showIntent */ pi)
        runCatching {
            am.setAlarmClock(info, pi)
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "AlarmScheduler scheduled id=${alarm.id} triggerAt=$triggerAt"
            )
        }.onFailure {
            com.powermediaplayer.util.Diag.w(
                "PMP_DIAG",
                "AlarmScheduler.schedule failed (need USE_EXACT_ALARM perm?)",
                it
            )
        }
    }

    /**
     * §C12 — schedule a one-shot snooze fire for [snoozeCount] minutes
     * from now, identified by [snoozeAlarmId] so it can be cancelled
     * independently of the master alarm. The receiver's `snoozeCount`
     * extra is incremented so the activity can enforce maxSnoozes.
     */
    fun scheduleSnooze(
        context: Context,
        original: AlarmRecord,
        snoozeAlarmId: Long,
        snoozeCount: Int
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + original.snoozeMinutes * 60_000L
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, original.id)
            putExtra(AlarmReceiver.EXTRA_SNOOZE_COUNT, snoozeCount)
        }
        val pi = PendingIntent.getBroadcast(
            context, snoozeAlarmId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "AlarmScheduler snooze scheduled id=${original.id} " +
                    "snoozeCount=$snoozeCount triggerAt=$triggerAt"
            )
        }.onFailure {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "AlarmScheduler.scheduleSnooze failed", it)
        }
    }

    fun cancel(context: Context, alarmId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, alarmId, flags = PendingIntent.FLAG_NO_CREATE)
            ?: return
        am.cancel(pi)
        pi.cancel()
    }

    /** First future time matching the alarm's weekday mask + time-of-day. */
    private fun computeNextTriggerMs(alarm: AlarmRecord): Long {
        val now = Calendar.getInstance()
        // For one-shot (days == 0) the alarm fires the next time
        // hour:minute occurs (today if still in the future, otherwise
        // tomorrow).
        if (alarm.days == 0) {
            val cal = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now.timeInMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
        // Recurring — find the soonest weekday in the next 7 days that
        // matches the day-mask. Calendar.MONDAY = 2 → bit 0; SUNDAY = 1
        // → bit 6 in our mask.
        for (i in 0 until 7) {
            val cal = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, i)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now.timeInMillis) continue
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val bit = when (dow) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 4
                Calendar.THURSDAY -> 8
                Calendar.FRIDAY -> 16
                Calendar.SATURDAY -> 32
                Calendar.SUNDAY -> 64
                else -> 0
            }
            if (alarm.days and bit != 0) return cal.timeInMillis
        }
        return -1L
    }

    private fun pendingIntent(
        context: Context,
        alarmId: Long,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context, alarmId.toInt(), intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
