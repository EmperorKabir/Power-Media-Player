package com.powermediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.adaptive.AdaptiveInfo
import com.powermediaplayer.ui.player.PlayerViewModel
import com.powermediaplayer.ui.player.components.VideoSurface
import com.powermediaplayer.ui.theme.TextPrimary
import kotlin.math.roundToInt

/**
 * Single hoisted video stage.
 *
 * Hosts exactly ONE [VideoSurface] call site for the lifetime of the app,
 * mounted in AppNavigation OUTSIDE the NavHost so it is never disposed on a
 * tab switch. Its wrapping modifier (size/position/zIndex) changes by route,
 * but the call site is the same, so Compose never recreates the underlying
 * AndroidView/TextureView — the codec stays attached and the 1-frame black
 * flash on returning to the Player tab is eliminated.
 *
 * Route behaviour:
 *   - Player route   → fills the content area BEHIND the NavHost (zIndex 0);
 *     PlayerScreen shows it through a transparent hole. Tabletop posture →
 *     pinned to the top leaf.
 *   - any other tab  → a small draggable in-app PiP window (zIndex 2), with
 *     drag + clamp + ✕ dismiss chrome (the old FloatingVideoMiniPlayer).
 */
@Composable
fun HoistedVideoStage(
    isPlayerRoute: Boolean,
    adaptive: AdaptiveInfo?,
    onExpand: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    if (!uiState.isVideoContent || !uiState.hasMedia) return

    val tabletop = adaptive?.isTabletop == true && adaptive.hingeBounds != null
    val topLeafDp = if (tabletop) {
        with(LocalDensity.current) { adaptive!!.hingeBounds!!.top.toDp() }
    } else {
        0.dp
    }

    // Mini-window drag state (reused from FloatingVideoMiniPlayer).
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var selfSize by remember { mutableStateOf(IntSize.Zero) }
    // ✕ hides the window for the CURRENT item only — a new video (or returning
    // later) brings it back.
    var dismissed by remember(uiState.title) { mutableStateOf(false) }
    fun clampOffsets() {
        // Anchored BottomEnd: legal offsets are ≤0 (leftwards/upwards).
        val maxLeft = (containerSize.width - selfSize.width).coerceAtLeast(0).toFloat()
        val maxUp = (containerSize.height - selfSize.height).coerceAtLeast(0).toFloat()
        offsetX = offsetX.coerceIn(-maxLeft, 0f)
        offsetY = offsetY.coerceIn(-maxUp, 0f)
    }
    LaunchedEffect(containerSize, selfSize) { clampOffsets() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Player route → behind the NavHost content; other tabs → above it.
            .zIndex(if (isPlayerRoute) 0f else 2f)
            .onSizeChanged { containerSize = it }
    ) {
        // ONE call site — the wrapping modifier changes by route, the call does
        // not. This is the whole fix: the TextureView is never recreated.
        val surfaceMod: Modifier = when {
            !isPlayerRoute -> {
                if (dismissed) {
                    // Keep the surface composed but invisible so it is NOT
                    // recreated when un-dismissed.
                    Modifier.size(0.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp)
                        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                        .size(width = 192.dp, height = 108.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .onSizeChanged { selfSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                offsetX += drag.x
                                offsetY += drag.y
                                clampOffsets()
                            }
                        }
                        .clickable(onClick = onExpand)
                }
            }
            tabletop -> Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(topLeafDp)
            else -> Modifier.fillMaxSize()
        }

        VideoSurface(
            isVideoContent = true,
            videoWidth = uiState.videoWidth,
            videoHeight = uiState.videoHeight,
            modifier = surfaceMod
        )

        // ✕ dismiss button — only in the mini window, mirroring the surface's
        // top-right corner with the same align/padding/offset/size math.
        if (!isPlayerRoute && !dismissed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(width = 192.dp, height = 108.dp)
            ) {
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
