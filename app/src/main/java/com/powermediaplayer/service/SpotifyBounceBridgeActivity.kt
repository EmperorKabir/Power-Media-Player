package com.powermediaplayer.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.powermediaplayer.MainActivity

/**
 * INVESTIGATION-PROTOTYPE for fix-shape (c) of the cold-start bounce
 * RCA at docs/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/.
 *
 * Theory: Samsung One UI's `balDontBringExistingBackgroundTaskStackToFg`
 * policy + `realInVisibleTask=false` rejects our 1500-ms-deferred
 * bouncePi.send() because by the time it fires, our task isn't visible
 * (Spotify slid in front). If we instead launch a translucent, no-history
 * activity OF OURS first, then have *that* activity launch Spotify and
 * schedule the bounce, the bridge is the actual caller of the bounce
 * intent. Whether Samsung's policy honours "task contained a recently-
 * visible activity" is the empirical question this prototype answers.
 *
 * If a cold-start run with this bridge in place still emits BAL_BLOCK in
 * ActivityTaskManager, the prototype has falsified fix-shape (c) and we
 * fall back to (b). If the run shows BAL_ALLOW, ship.
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
