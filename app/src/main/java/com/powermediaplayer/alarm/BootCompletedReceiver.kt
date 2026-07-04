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
                // Audit §10.1/B-13 Tier 1: at LOCKED_BOOT_COMPLETED the DataStore
                // file lives in credential-encrypted storage and is unreadable
                // before first unlock — a throw here escaped the scope (no
                // exception handler) and could crash the process at every boot.
                // Degrade to a no-op: the post-unlock BOOT_COMPLETED delivery
                // re-arms everything. (Pre-unlock re-arm needs a device-protected
                // store — recorded as a separate user decision.)
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) {
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "Boot reschedule skipped: user locked (action=$action); post-unlock delivery re-arms"
                    )
                    return@launch
                }
                runCatching {
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
                }.onFailure {
                    com.powermediaplayer.util.Diag.e("PMP_DIAG", "Boot reschedule failed", it)
                }
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
