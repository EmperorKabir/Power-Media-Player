package com.powermediaplayer.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

/**
 * Single source of adaptive truth passed through the UI tree (audit 8.1/8.2).
 * windowSizeClass is already computed in MainActivity; posture comes from
 * material3-adaptive (wraps androidx.window WindowInfoTracker).
 *
 * hinge* fields describe the FIRST hinge (all current foldables have exactly
 * one); null/false when flat or on a slab device.
 */
@Immutable
data class AdaptiveInfo(
    val widthClass: WindowWidthSizeClass,
    val heightClass: WindowHeightSizeClass,
    val isTabletop: Boolean,
    val hingeBounds: Rect?,
    val hingeIsVertical: Boolean,
    val hingeIsSeparating: Boolean,
)

@Composable
fun rememberAdaptiveInfo(windowSizeClass: WindowSizeClass): AdaptiveInfo {
    val posture = currentWindowAdaptiveInfo().windowPosture
    val hinge = posture.hingeList.firstOrNull()
    val info = AdaptiveInfo(
        widthClass = windowSizeClass.widthSizeClass,
        heightClass = windowSizeClass.heightSizeClass,
        isTabletop = posture.isTabletop,
        hingeBounds = hinge?.bounds,
        hingeIsVertical = hinge?.isVertical == true,
        hingeIsSeparating = hinge?.isSeparating == true,
    )
    // DIAGNOSTIC (F8) — log what material3-adaptive actually computes from
    // the posture, so a fold shows whether isTabletop ever flips for the
    // app (cross-check against the raw FoldingFeature logged in MainActivity).
    androidx.compose.runtime.LaunchedEffect(
        info.isTabletop, info.hingeBounds, info.widthClass, info.heightClass
    ) {
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "[POSTURE] adaptiveInfo isTabletop=${info.isTabletop} " +
                "hingeBounds=${info.hingeBounds} vertical=${info.hingeIsVertical} " +
                "separating=${info.hingeIsSeparating} width=${info.widthClass} height=${info.heightClass}"
        )
    }
    return info
}
