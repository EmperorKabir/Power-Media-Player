package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary

/**
 * #79 — direct cast-device switching.
 *
 * Stock [androidx.mediarouter.app.MediaRouteButton] behaviour: tap when
 * NO session is active opens the route chooser; tap when a session IS
 * active opens the controller dialog (current device + STOP). To switch
 * to a different device the user must STOP the current session, tap
 * again, then pick the new device — three taps minimum.
 *
 * This component sits next to [CastButton] and exposes a one-tap
 * switcher. When no session is active it stays hidden so the standard
 * Cast button is the only entry. When a session IS active, the icon
 * lights up; tapping opens our own bottom sheet listing every Cast
 * route the system has discovered, with the current device highlighted
 * and a Stop entry. Picking a different route calls
 * `MediaRouter.selectRoute(...)`; the Cast SDK's SessionManager then
 * tears down the old session (our `onSessionEnding` handler clears
 * receiver media so it stops) and starts the new one (our
 * `onCastSessionAvailable` handler re-queues via switchPlayer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSwitcherButton(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .build()
    }
    // Discovery callback — keeps a live list of cast routes. Registered
    // with REQUEST_DISCOVERY so we get scan updates while the screen is
    // open.
    val routes: SnapshotStateList<MediaRouter.RouteInfo> = remember { mutableStateListOf() }
    var selectedRouteId by remember { mutableStateOf(router.selectedRoute.id) }
    DisposableEffect(Unit) {
        val cb = object : MediaRouter.Callback() {
            private fun refresh() {
                routes.clear()
                routes.addAll(
                    router.routes.filter {
                        it.matchesSelector(selector) && !it.isDefault
                    }
                )
                selectedRouteId = router.selectedRoute.id
            }
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(
                r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int
            ) = refresh()
            override fun onRouteUnselected(
                r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int
            ) = refresh()
        }
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        // Seed with the current state.
        cb.onRouteAdded(router, router.selectedRoute)
        onDispose { router.removeCallback(cb) }
    }
    val isCasting = !router.selectedRoute.isDefault
    if (!isCasting) return // hidden until a session is active

    var sheetOpen by remember { mutableStateOf(false) }

    IconButton(
        onClick = { sheetOpen = true },
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CastConnected,
            contentDescription = "Switch cast device",
            tint = TealAccent
        )
    }

    if (sheetOpen) {
        PopupOpenGuard()
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            containerColor = OledBlack
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Cast to a different device",
                    style = MaterialTheme.typography.titleMedium,
                    color = TealAccent
                )
                Spacer(Modifier.height(8.dp))
                if (routes.isEmpty()) {
                    Text(
                        text = "No Cast devices discovered yet — make sure they're on the same Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    routes.forEach { route ->
                        DeviceRow(
                            name = route.name,
                            description = route.description.orEmpty(),
                            isSelected = route.id == selectedRouteId,
                            onClick = {
                                router.selectRoute(route)
                                sheetOpen = false
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                StopRow(
                    onClick = {
                        // Selecting the default route is the documented
                        // way to end the cast session via MediaRouter.
                        router.selectRoute(router.defaultRoute)
                        sheetOpen = false
                    }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DeviceRow(
    name: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) TealAccent.copy(alpha = 0.15f) else SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = !isSelected, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Speaker,
                contentDescription = null,
                tint = if (isSelected) TealAccent else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) TealAccent else TextPrimary
                )
                if (description.isNotBlank()) {
                    Text(
                        text = if (isSelected) "Currently casting" else description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                } else if (isSelected) {
                    Text(
                        text = "Currently casting",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun StopRow(onClick: () -> Unit) {
    Surface(
        color = Color(0x33CF6679),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                tint = Color(0xFFCF6679),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Stop casting",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFCF6679)
            )
        }
    }
}
