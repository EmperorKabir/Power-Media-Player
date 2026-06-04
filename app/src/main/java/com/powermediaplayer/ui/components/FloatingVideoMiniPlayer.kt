package com.powermediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.player.PlayerViewModel
import com.powermediaplayer.ui.player.components.VideoSurface
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TextPrimary
import kotlin.math.roundToInt

/**
 * In-app picture-in-picture: a small draggable video window shown on
 * every non-Player tab while video content is loaded, so moving around
 * the app doesn't lose the picture. System PiP (pressing Home / leaving
 * the app) is untouched — this covers the in-app case the OS cannot.
 *
 * Reuses [VideoSurface], so the colour-matrix and mirror/rotation video
 * effects apply here exactly as on the Player tab, and the player's
 * single video surface hands off automatically: VideoSurface binds the
 * TextureView in its factory and clears it on release, the same
 * mechanism the system-PiP branch uses.
 *
 * Tap → back to the Player tab. ✕ → hide until the item changes.
 */
@Composable
fun FloatingVideoMiniPlayer(
    onExpand: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ✕ hides the window for the CURRENT item only — a new video (or
    // returning later) brings it back.
    var dismissed by remember(uiState.title) { mutableStateOf(false) }
    if (!uiState.isVideoContent || !uiState.hasMedia || dismissed) return

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // The container does not consume touches — only the window does.
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = OledBlack,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(width = 192.dp, height = 108.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offsetX += drag.x
                        offsetY += drag.y
                    }
                }
        ) {
            Box(modifier = Modifier.fillMaxSize().clickable(onClick = onExpand)) {
                VideoSurface(
                    isVideoContent = true,
                    videoWidth = uiState.videoWidth,
                    videoHeight = uiState.videoHeight,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .clickable { dismissed = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Hide mini player",
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
