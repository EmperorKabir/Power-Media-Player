package com.powermediaplayer.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.powermediaplayer.MainActivity

/**
 * Translucent bridge activity that owns the Spotify auto-launch and the
 * deferred bounce-back. Lives in MainActivity's task (no taskAffinity
 * override) so the deferred startActivity falls under Android's
 * BAL grace-period exemption (system logs the launch as
 * `BAL_ALLOW_GRACE_PERIOD`).
 *
 * Background: the previous PendingIntent.send-from-pollScope approach
 * was BAL_BLOCK'd by Samsung One UI's
 * `balDontBringExistingBackgroundTaskStackToFg=true` policy on cold
 * start (see RCA at
 * docs/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/).
 * This bridge sidesteps that policy because the bounce intent's caller
 * is an Activity (this one) that was visible ≤ 1.5 s before the bounce,
 * which qualifies for the BAL grace period.
 *
 * MUST NOT have `noHistory=true` in the manifest — that auto-finishes
 * the bridge the moment Spotify takes foreground, killing the deferred
 * handler before the bounce can fire (verified empirically).
 */
class SpotifyBounceBridgeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("PMP_DIAG", "BounceBridge.onCreate")

        // (1) Foreground service for BAL exemption — same as before.
        SpotifyBounceService.start(this)

        // (2) Spotify launch.
        val spotify = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (spotify == null) {
            android.util.Log.w("PMP_DIAG", "BounceBridge: Spotify not installed; finishing without bounce")
            finish()
            return
        }
        spotify.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
            Intent.FLAG_ACTIVITY_NO_ANIMATION
        startActivity(spotify)
        android.util.Log.i("PMP_DIAG", "BounceBridge: Spotify auto-launch fired")

        // (3) Bounce back to MainActivity 1500 ms later. Crucially, this
        //     startActivity is invoked FROM this bridge — which is part
        //     of MainActivity's task. The system's `realInVisibleTask`
        //     check is then evaluated against this task, which contained
        //     a visible activity ≤ 1500 ms prior. Whether Samsung's
        //     `balDontBringExistingBackgroundTaskStackToFg` overrides
        //     this is what the test will reveal.
        handler.postDelayed({
            val bounce = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            runCatching {
                startActivity(bounce)
                android.util.Log.i("PMP_DIAG", "BounceBridge: bounce startActivity dispatched")
            }.onFailure { e ->
                android.util.Log.w("PMP_DIAG", "BounceBridge: bounce startActivity failed", e)
            }
            finish()
        }, 1500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        android.util.Log.i("PMP_DIAG", "BounceBridge.onDestroy")
        super.onDestroy()
    }
}
