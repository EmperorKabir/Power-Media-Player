package com.powermediaplayer.cloud

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AC3 fix — AppAuth PendingIntent completion target for Spotify sign-in.
 *
 * The old flow returned the auth result via `rememberLauncherForActivityResult`
 * (startActivityForResult). Device-proven root cause (2026-08-02): when ColorOS
 * HARD-KILLS the whole app process while the Spotify Custom Tab is foreground,
 * tapping Agree relaunches AppAuth's RedirectUriReceiver→AuthorizationManagement
 * in a fresh process, which then finishes WITHOUT delivering — the in-memory
 * result callback died with the process, so the token exchange never runs and the
 * sign-in sticks. ("Don't keep activities" does NOT reproduce it — the process
 * survives there and the callback fires; it needs a full process kill.)
 *
 * A PendingIntent is an OS-held durable token, so AppAuth can fire THIS Activity
 * even after that kill. It completes the exact token exchange the old callback
 * dropped. This is AppAuth's documented, cross-platform approach (works on all
 * Android; strictly more robust than the launcher path everywhere — stock Android
 * also kills backgrounded apps under memory pressure, just less aggressively).
 *
 * Not exported: launched only by the app's own completion PendingIntent.
 */
@AndroidEntryPoint
class SpotifyAuthCompleteActivity : ComponentActivity() {
    @Inject lateinit var spotifyProvider: SpotifyProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AppAuth put the AuthorizationResponse (or exception) into this Intent's extras.
        val data: Intent = intent
        com.powermediaplayer.diag.DiagLog.event(
            "SPOTIFYAUTH", "completion Activity fired (PendingIntent) — survives full process kill"
        )
        // Mirror CloudViewModel.handleSpotifyResult: clear the in-flight deadline, then
        // exchange + persist the token (SpotifyProvider is @Singleton; the reactive
        // isLoggedIn flow flips the Cloud card signed-in once the token is written).
        com.powermediaplayer.service.PlaybackService.clearOauthInFlight()
        lifecycleScope.launch {
            runCatching { spotifyProvider.handleAuthResponse(data) }
                .onFailure {
                    com.powermediaplayer.diag.DiagLog.event(
                        "SPOTIFYAUTH", "completion handleAuthResponse threw ${it.javaClass.simpleName}"
                    )
                }
            // Bring the app back to front (signed in). CLEAR_TOP|SINGLE_TOP reuses the
            // existing MainActivity in the warm case and starts it fresh after a kill.
            runCatching {
                startActivity(
                    Intent(this@SpotifyAuthCompleteActivity, com.powermediaplayer.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            finish()
        }
    }
}
