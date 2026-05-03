package com.powermediaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.ui.navigation.AppNavigation
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.PowerMediaPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity host for the entire Compose UI.
 * Computes WindowSizeClass once here and passes it to navigation
 * so all screens can adapt to phone/tablet/foldable widths.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playbackConnection: PlaybackConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playbackConnection.connect()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            PowerMediaPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OledBlack
                ) {
                    AppNavigation(windowSizeClass = windowSizeClass)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            playbackConnection.disconnect()
        }
    }

    /**
     * Auto-enter Picture-in-Picture when the user presses Home while a
     * video is playing. Audio playback continues via the MediaSession
     * notification (no PiP needed). Spotify mirror playback is also
     * notification-driven, so we only enter PiP for local/Drive video.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = playbackConnection.playerState.value
        val isVideo = state.isVideoContent
        val isPlaying = state.isPlaying
        if (isVideo && isPlaying &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        ) {
            runCatching {
                val w = state.videoWidth.coerceAtLeast(16)
                val h = state.videoHeight.coerceAtLeast(9)
                // PiP aspect ratio is clamped by the system (~2.39:1 to 1:2.39).
                val aspect = android.util.Rational(w, h)
                val safeAspect = if (aspect.toFloat() > 2.39f || aspect.toFloat() < 0.42f) {
                    android.util.Rational(16, 9)
                } else aspect
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(safeAspect)
                    .build()
                enterPictureInPictureMode(params)
            }.onFailure {
                android.util.Log.w("PMP_DIAG", "PiP enter failed", it)
            }
        }
    }
}
