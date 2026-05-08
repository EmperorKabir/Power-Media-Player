package com.powermediaplayer.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §C12 — re-schedule every saved alarm after device reboot. Without
 * this, AlarmManager-registered alarms vanish on power-cycle (the
 * system clears the alarm queue across boots). The manifest already
 * declares the RECEIVE_BOOT_COMPLETED permission and registers this
 * receiver for ACTION_BOOT_COMPLETED + ACTION_LOCKED_BOOT_COMPLETED
 * + ACTION_MY_PACKAGE_REPLACED so app updates also trigger a re-arm.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACCEPTED) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                val alarms = settingsDataStore.scheduledAlarms.first()
                var rescheduled = 0
                alarms.filter { it.enabled }.forEach { alarm ->
                    AlarmScheduler.schedule(context, alarm)
                    rescheduled++
                }
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "Boot reschedule: $rescheduled alarm(s) re-armed (action=$action)"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val ACCEPTED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
