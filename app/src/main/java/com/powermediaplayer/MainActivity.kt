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
}
