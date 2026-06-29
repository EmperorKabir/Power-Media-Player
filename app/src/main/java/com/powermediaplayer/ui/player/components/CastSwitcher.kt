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
import androidx.compose.material3.HorizontalDivider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.settings.SettingsViewModel
import com.powermediaplayer.ui.theme.DisabledContent

/**
 * Combined Cast button — always visible, replaces the previous pair
 * (CastButton + CastSwitcherButton). Tapping always opens the sheet:
 *
 *   - When no session is active: sheet shows the device chooser
 *     (every discovered Cast route). Picking a device starts a session
 *   - When a session IS active: sheet shows the same chooser PLUS
 *     a Stop Casting row at the bottom. Tapping a different device
 *     transfers the session; tapping Stop ends it
 *
 * The icon changes state to match: CastConnected (teal solid) when a
 * session is active, Cast (teal dim) when idle. Always tappable, never
 * hidden. The earlier two-button layout (separate CastButton + Cast
 * Switcher) was redundant — users couldn't tell them apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSwitcherButton(
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by settingsVm.uiState.collectAsStateWithLifecycle()
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
    var sheetOpen by remember { mutableStateOf(false) }
    val cb = remember {
        object : MediaRouter.Callback() {
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
    }
    // Audit 3.9 — REQUEST_DISCOVERY keeps Wi-Fi mDNS scanning alive for
    // the button's whole lifetime (i.e. whenever the Player tab shows).
    // Passive registration is enough for the icon's connected state;
    // active scanning runs only while the route sheet is open. Re-adding
    // the same callback updates its flags in place.
    DisposableEffect(sheetOpen) {
        val flags = if (sheetOpen) MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY else 0
        router.addCallback(selector, cb, flags)
        // Seed with the current state.
        cb.onRouteAdded(router, router.selectedRoute)
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { router.removeCallback(cb) }
    }
    // "Casting" = a genuine REMOTE route is selected, NOT merely a non-default
    // one. A connected Bluetooth speaker is ALSO a non-default system route
    // (logcat: playbackType=0 = LOCAL), so the old `!isDefault` test lit the
    // cast button "CastConnected" and showed a "Stop casting" row whenever BT
    // was connected — even with no cast session. Tapping that phantom Stop ran
    // selectRoute(defaultRoute), which yanked audio off the BT speaker onto the
    // phone. Bluetooth AND the phone are both PLAYBACK_TYPE_LOCAL; only
    // Chromecast / remote-playback routes are PLAYBACK_TYPE_REMOTE.
    val isCasting = remember(selectedRouteId) {
        val r = router.selectedRoute
        !r.isDefault &&
            r.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
    }

    IconButton(
        onClick = { sheetOpen = true },
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            imageVector = if (isCasting) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = if (isCasting) "Cast (currently casting — tap to switch or stop)" else "Cast to a device",
            tint = if (isCasting) TealAccent else TealAccent.copy(alpha = 0.7f)
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
                    text = if (isCasting) "Cast — switch device or stop" else "Cast to a device",
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
                                // Deliberate device pick → clear any stop-suppression so
                                // the app casts/switches normally.
                                com.powermediaplayer.service.PlaybackService.notifyUserRequestedCast()
                                router.selectRoute(route)
                                sheetOpen = false
                            }
                        )
                    }
                }
                // Stop Casting row only when an active session exists.
                // When idle, just the device list is shown.
                if (isCasting) {
                    Spacer(Modifier.height(8.dp))
                    StopRow(
                        onClick = {
                            // Explicit stop → arm the suppression window so a
                            // framework-re-armed persistent route can't auto-cast the
                            // item onto a stray device (the TV→speaker hijack).
                            com.powermediaplayer.service.PlaybackService.notifyUserStoppedCast()
                            // Return audio to the connected Bluetooth speaker if
                            // there is one — selectRoute(defaultRoute) ALWAYS
                            // forces the phone built-in, which left audio on the
                            // phone after stopping a cast while BT stayed
                            // connected (logcat 21:48:55: "Selecting route: Phone"
                            // → builtin speaker (2)). isBluetooth() is the stable
                            // mediarouter 1.7 discriminator.
                            val btRoute = router.routes.firstOrNull { it.isBluetooth }
                            router.selectRoute(btRoute ?: router.defaultRoute)
                            sheetOpen = false
                        }
                    )
                }
                // ── Video / audio sync offset ────────────────────────
                // Same stored value as Settings → "Cast video audio offset",
                // so adjusting it here moves the Settings slider and vice-versa.
                // Applies when watching video on the phone while casting the
                // audio to an audio-only device.
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DisabledContent)
                Spacer(Modifier.height(12.dp))
                AvSyncOffsetControl(
                    title = "Video / audio sync offset",
                    description = "When you cast audio to a speaker, the video " +
                        "keeps playing on your phone. Nudge this if the picture " +
                        "leads or lags the cast audio. Range ±1 second.",
                    offsetMs = settings.castVideoAudioOffsetMs,
                    onOffsetChange = { settingsVm.setCastVideoAudioOffsetMs(it) }
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
