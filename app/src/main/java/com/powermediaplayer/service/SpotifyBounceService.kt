package com.powermediaplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Tiny foreground service that lives for ~10 seconds while we're
 * launching Spotify and bouncing back. It exists for exactly one
 * reason: Android's BAL (background-activity-launch) policy on
 * Android 14+ and Samsung One UI 6+ blocks ALL activity launches
 * from a background process — even ones fired through AlarmManager
 * with PendingIntent. A foreground service grants our process the
 * "foreground" status that BAL respects, so the bounce-back launch
 * goes through.
 *
 * The service self-stops after 10 s. The user sees a brief silent
 * notification ("Switching to Spotify…") that auto-dismisses.
 */
class SpotifyBounceService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Switching to Spotify…")
            .setContentText("Bringing the app back in a moment.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Self-stop after 10 s — long enough for Spotify launch +
        // our bounce-back PendingIntent to fire even if the user is
        // on a slow phone.
        handler.postDelayed({
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "SpotifyBounceService self-stop")
            stopSelf()
        }, 10_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Spotify launch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Brief notification while switching to Spotify"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "spotify_bounce"
        private const val NOTIF_ID = 4173

        /**
         * Start the service. Caller must currently be in a foreground
         * state (a UI tap counts) — Android's foreground-service-from-
         * background restrictions otherwise reject the start.
         */
        fun start(context: Context) {
            val intent = Intent(context, SpotifyBounceService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "SpotifyBounceService started")
            }.onFailure {
                com.powermediaplayer.util.Diag.w("PMP_DIAG", "SpotifyBounceService start failed", it)
            }
        }
    }
}
