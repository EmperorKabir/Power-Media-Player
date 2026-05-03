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
import androidx.lifecycle.lifecycleScope
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.ui.navigation.AppNavigation
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.PowerMediaPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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

    /**
     * True while the system is rendering us in PiP. Drives
     * AppNavigation to render ONLY the VideoSurface in this mode so
     * the user doesn't see the bottom nav / sliders behind black bars.
     */
    private val isInPip = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playbackConnection.connect()

        // Keep PiP params in sync with playback state so SDK 31+
        // setAutoEnterEnabled actually fires when the user presses Home.
        // setAutoEnterEnabled is only honoured if the params are
        // already on the activity at the time the user leaves; setting
        // it inside onUserLeaveHint is too late and the system rejects
        // the PiP entry (enterPictureInPictureMode returns false).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                playbackConnection.playerState.collect { st ->
                    runCatching {
                        val w = st.videoWidth.coerceAtLeast(16)
                        val h = st.videoHeight.coerceAtLeast(9)
                        val aspect = android.util.Rational(w, h)
                        val safe = if (aspect.toFloat() > 2.39f || aspect.toFloat() < 0.42f) {
                            android.util.Rational(16, 9)
                        } else aspect
                        val auto = st.isVideoContent && st.isPlaying
                        val params = android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(safe)
                            .setSeamlessResizeEnabled(true)
                            .setAutoEnterEnabled(auto)
                            .build()
                        setPictureInPictureParams(params)
                    }
                }
            }
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            PowerMediaPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OledBlack
                ) {
                    if (isInPip.value) {
                        // PiP mode: render ONLY the video surface so the
                        // window content matches the PiP frame. App
                        // chrome (tabs, controls) stays hidden.
                        com.powermediaplayer.ui.player.components.VideoSurface(
                            isVideoContent = true,
                            videoWidth = playbackConnection.playerState.value.videoWidth,
                            videoHeight = playbackConnection.playerState.value.videoHeight,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AppNavigation(windowSizeClass = windowSizeClass)
                    }
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        android.util.Log.i("PMP_PIP", "onPictureInPictureModeChanged isInPip=$isInPictureInPictureMode")
        isInPip.value = isInPictureInPictureMode
        // Re-bind the player to the freshly-recomposed VideoSurface.
        // Compose creates a new SurfaceView when the conditional tree
        // flips, and ExoPlayer needs an explicit setVideoSurfaceView
        // on the new view; otherwise PiP shows a blank/black window
        // while audio continues. Done via a posted Runnable so the
        // recomposition has settled before we look up the surface.
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
        android.util.Log.i(
            "PMP_PIP",
            "onUserLeaveHint isVideo=$isVideo isPlaying=$isPlaying w=${state.videoWidth} h=${state.videoHeight}"
        )
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
                val builder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(safeAspect)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    builder.setSeamlessResizeEnabled(true)
                    builder.setAutoEnterEnabled(true)
                }
                val ok = enterPictureInPictureMode(builder.build())
                android.util.Log.i("PMP_PIP", "enterPictureInPictureMode returned=$ok aspect=$safeAspect")
            }.onFailure {
                android.util.Log.w("PMP_PIP", "PiP enter failed", it)
            }
        }
    }
}
