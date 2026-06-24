package com.powermediaplayer.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.powermediaplayer.ui.cloud.CloudBrowserScreen
import com.powermediaplayer.ui.equalizer.EqualizerScreen
import com.powermediaplayer.ui.library.LibraryScreen
import com.powermediaplayer.ui.library.LibraryViewModel
import com.powermediaplayer.ui.player.PlayerScreen
import com.powermediaplayer.ui.settings.SettingsScreen
import com.powermediaplayer.ui.theme.DisabledGrey
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TealAccent

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Player : Screen("player", "Player", Icons.Filled.PlayCircle)
    data object Library : Screen("library", "Library", Icons.Filled.LibraryMusic)
    data object LastPlayed : Screen("last_played", "Last Played", Icons.Filled.History)
    data object Cloud : Screen("cloud", "Cloud", Icons.Filled.Cloud)
    data object Equalizer : Screen("equalizer", "EQ", Icons.Filled.Equalizer)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    // Secondary screen — reachable from the Cloud tab + Settings, not a tab.
    data object Downloads : Screen("downloads", "Downloads", Icons.Filled.Download)
}

private val screens = listOf(
    Screen.Player, Screen.Library, Screen.LastPlayed,
    Screen.Cloud, Screen.Equalizer, Screen.Settings
)

/** Height of the immersive-video app-tab BOTTOM bar (compact/folded widths) —
 *  the Material3 NavigationBar content height, excluding the system nav inset
 *  (the bar adds that itself). The transport stack reserves this much bottom
 *  space while shown so they never collide. */
internal val ImmersiveVideoTabBarHeight = 80.dp

/** Width of the immersive-video app-tab SIDE rail (expanded/unfolded widths),
 *  matching the app's normal NavigationRail placement. The transport stack
 *  reserves this much START space while shown so the rail never overlaps. */
internal val ImmersiveVideoRailWidth = 80.dp

/**
 * Main app navigation. Hosts a SHARED LibraryViewModel across the Library tab
 * so that the "navigate to player" action can trigger playback and switch tabs
 * atomically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavigation(
    windowSizeClass: WindowSizeClass,
    initialOpenTab: String? = null,
    onOpenTabConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // §C20 — handle widget tap deep-link. When the widget host launched
    // us with EXTRA_OPEN_TAB="player" (the only value we currently
    // surface), force-navigate to the Player route, then CONSUME the
    // value (audit 6.2: it was never cleared, so any activity
    // recreation — fold/unfold density change, theme switch, font-size
    // change — re-fired the navigation and dumped the user onto the
    // Player tab from wherever they were).
    androidx.compose.runtime.LaunchedEffect(initialOpenTab) {
        when (initialOpenTab) {
            "player" -> {
                navController.navigate(Screen.Player.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                onOpenTabConsumed()
            }
        }
    }

    // Shared ViewModel scoped to the NavGraph host — allows LibraryScreen to
    // trigger playback and then navigate to the Player tab in one tap.
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    // vc32: drill-ins PUSH the Player so back returns to the list the
    // user came from (Last Played / Library / Cloud / mini-player). The
    // previous popUpTo-wipe made every back press exit the whole app —
    // especially painful while waiting on a slow resume. Bottom-bar TAB
    // taps keep their canonical
    // popUpTo(start){saveState} pattern (unchanged below) so tab presses
    // still reset the stack and growth stays bounded.
    val navigateToPlayer = {
        navController.navigate(Screen.Player.route) {
            launchSingleTop = true
        }
    }

    // vc31 — empty-player guidance jumps the user to the Library tab.
    val navigateToLibrary = {
        navController.navigate(Screen.Library.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val isPlayerRoute = currentDestination?.hierarchy?.any { it.route == Screen.Player.route } == true

    // T294 — the app nav is now an always-present alpha OVERLAY (built below),
    // NOT a NavigationSuiteScaffold layout sibling. The scaffold's own nav is
    // forced OFF (layoutType None) for EVERY route, so the content slot never
    // resizes when entering the full-screen video player (that resize — nav
    // bar/rail vanishing + content re-padding — was the "all tabs refresh"
    // flicker). Width still decides bar (compact/folded) vs side rail
    // (expanded/unfolded) for the overlay and the per-route content inset.
    val compactWidth = windowSizeClass.widthSizeClass ==
        androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact
    val navLayoutType =
        androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.None
    val suiteColors =
        androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults.colors(
            navigationBarContainerColor = OledBlack,
            navigationRailContainerColor = OledBlack
        )
    // item()'s builder lambda is not composable — build the colours here.
    val suiteItemColors =
        androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults.itemColors(
            navigationBarItemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = TealAccent,
                selectedTextColor = TealAccent,
                unselectedIconColor = DisabledGrey,
                unselectedTextColor = DisabledGrey,
                indicatorColor = OledBlack
            ),
            navigationRailItemColors =
                androidx.compose.material3.NavigationRailItemDefaults.colors(
                    selectedIconColor = TealAccent,
                    selectedTextColor = TealAccent,
                    unselectedIconColor = DisabledGrey,
                    unselectedTextColor = DisabledGrey,
                    indicatorColor = OledBlack
                )
        )
    androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold(
        layoutType = navLayoutType,
        containerColor = OledBlack,
        navigationSuiteColors = suiteColors,
        navigationSuiteItems = {
            screens.forEach { screen ->
                item(
                    icon = { Icon(imageVector = screen.icon, contentDescription = screen.title) },
                    label = { Text(text = screen.title, style = MaterialTheme.typography.labelSmall) },
                    selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                    onClick = {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    colors = suiteItemColors
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        // Content is FULL-BLEED for ALL routes — the scaffold shows no nav
        // (layoutType None), so the content slot never resizes when entering
        // the full-screen video player. The nav bar/rail is an always-present
        // OVERLAY (built at the bottom of this Box). Non-Player routes are
        // WRAPPED to reserve the same area the old scaffold slot gave (top
        // status bar + bottom-bar / side-rail clearance), so no individual
        // screen needed internal changes. The Player route is NOT wrapped — it
        // is full-bleed and self-insets inside PlayerScreen.
        val contentInset = Modifier
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
            .padding(
                bottom = if (compactWidth) ImmersiveVideoTabBarHeight else 0.dp,
                start = if (compactWidth) 0.dp else ImmersiveVideoRailWidth
            )
        NavHost(
            navController = navController,
            startDestination = Screen.Player.route,
            // T294 — instant swaps (no enter/exit transition); the default
            // cross-fade blended the outgoing tab under the incoming Player.
            enterTransition = { androidx.compose.animation.EnterTransition.None },
            exitTransition = { androidx.compose.animation.ExitTransition.None },
            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
            popExitTransition = { androidx.compose.animation.ExitTransition.None }
        ) {
            composable(Screen.Player.route) {
                PlayerScreen(
                    windowSizeClass = windowSizeClass,
                    adaptive = com.powermediaplayer.ui.adaptive.rememberAdaptiveInfo(windowSizeClass),
                    onNavigateToLibrary = navigateToLibrary
                )
            }
            composable(Screen.Library.route) {
                NonPlayerRoute(contentInset, navigateToPlayer) {
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onNavigateToPlayer = navigateToPlayer
                    )
                }
            }
            composable(Screen.Cloud.route) {
                NonPlayerRoute(contentInset, navigateToPlayer) {
                    CloudBrowserScreen(
                        onNavigateToPlayer = navigateToPlayer,
                        onOpenDownloads = { navController.navigate(Screen.Downloads.route) { launchSingleTop = true } }
                    )
                }
            }
            composable(Screen.Downloads.route) {
                NonPlayerRoute(contentInset, navigateToPlayer) {
                    com.powermediaplayer.ui.downloads.DownloadsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Screen.LastPlayed.route) {
                NonPlayerRoute(contentInset, navigateToPlayer) {
                    com.powermediaplayer.ui.lastplayed.LastPlayedScreen(
                        onNavigateToPlayer = navigateToPlayer
                    )
                }
            }
            composable(Screen.Equalizer.route) {
                NonPlayerRoute(contentInset, navigateToPlayer) { EqualizerScreen() }
            }
            composable(Screen.Settings.route) {
                NonPlayerRoute(contentInset, navigateToPlayer) {
                    SettingsScreen(
                        windowSizeClass = windowSizeClass,
                        onOpenDownloads = { navController.navigate(Screen.Downloads.route) { launchSingleTop = true } }
                    )
                }
            }
        }
        // In-app picture-in-picture floating video — SHARED across the
        // non-Player tabs (NOT per-route, or its video surface would re-bind on
        // every tab switch). Inset so its resting corner clears the nav overlay.
        // System PiP on leaving the app is unchanged (MainActivity's PiP branch).
        if (!isPlayerRoute) {
            // Reserve the MiniPlayerBar height (56dp) at the bottom too, so the
            // floating video's default BottomEnd rest position stays ABOVE the
            // mini-bar — its placement relative to the bar before this refactor.
            Box(modifier = Modifier.fillMaxSize().then(contentInset).padding(bottom = 56.dp)) {
                com.powermediaplayer.ui.components.FloatingVideoMiniPlayer(
                    onExpand = navigateToPlayer
                )
            }
        }
        // Always-present app-tab nav OVERLAY (replaces the scaffold's nav).
        // Visible on every normal tab AND the video player while controls are
        // up; fades out ONLY when the video controls hide. Because it's an
        // aligned overlay (never a layout sibling), showing/hiding it never
        // resizes content — no relayout, no flicker. Bottom bar on
        // compact/folded; SIDE rail on expanded/unfolded.
        val immersiveTabsOnSide = !compactWidth
        ImmersiveVideoTabOverlay(
            visible = !(com.powermediaplayer.MainActivityHolder.fullBleedVideo.value &&
                !com.powermediaplayer.MainActivityHolder.videoControlsVisible.value),
            useRail = immersiveTabsOnSide,
            currentDestination = currentDestination,
            modifier = Modifier.align(
                if (immersiveTabsOnSide) Alignment.CenterStart else Alignment.BottomCenter
            )
        ) { route ->
            // Leaving a DETAIL screen (Manage Downloads) → land on the tapped
            // tab's FRESH root, not a restored stack that still has Downloads on
            // top (that's why "tap Settings from Manage Downloads" didn't reach
            // the Settings top level). Normal tab→tab keeps state preservation.
            val onDetail = navController.currentDestination?.route == Screen.Downloads.route
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = !onDetail }
                launchSingleTop = true
                restoreState = !onDetail
            }
        }
        }
    }
}

/**
 * Wraps a NON-Player route so its content + mini-player bar occupy the same
 * area the old NavigationSuiteScaffold content slot gave: the full-bleed window
 * MINUS the top status bar and the bottom-bar / side-rail clearance for the
 * always-present nav overlay. The Player route is NOT wrapped (it is full-bleed
 * and self-insets inside PlayerScreen). Keeping the content area identical is
 * why no individual screen (Library/Cloud/LastPlayed/EQ/Settings) needed any
 * internal change. MiniPlayerBar is a layout sibling here (content sits above
 * it, exactly as before); it has no video surface, so composing it per-route is
 * cheap (no surface re-bind, unlike the shared FloatingVideoMiniPlayer).
 */
@Composable
private fun NonPlayerRoute(
    contentInset: Modifier,
    onMiniClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().then(contentInset)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        // Float the mini-player above the soft keyboard (the app is edge-to-edge,
        // so the IME draws over content — imePadding lifts the bar by the live IME
        // height and drops it back when the keyboard closes). Adaptive for free:
        // on phone/folded it rises above the bottom keyboard; on tablet/unfolded
        // the keyboard doesn't reach the side rail so there's nothing to avoid.
        Box(modifier = Modifier.imePadding()) {
            com.powermediaplayer.ui.components.MiniPlayerBar(onClick = onMiniClick)
        }
    }
}

/**
 * App-tab affordance overlaid on a full-bleed video while the controls are
 * visible. It floats ON TOP of the picture (never a layout sibling) so
 * showing/hiding tabs can't resize the video. Icons only; active tab tinted.
 * [useRail] true → a vertical SIDE rail on the start edge (expanded/unfolded,
 * matching the app's normal rail); false → a slim BOTTOM bar (compact/folded).
 * Adds the relevant system-bar insets itself.
 */
@Composable
private fun ImmersiveVideoTabOverlay(
    visible: Boolean,
    useRail: Boolean,
    currentDestination: NavDestination?,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    // Reuse the REAL Material3 nav components with the SAME colours as the
    // app-wide NavigationSuiteScaffold so the immersive overlay is visually
    // identical to the rail/bar shown on every other tab (top-aligned items,
    // icon + label, teal selected) — not a bespoke icon strip. They handle
    // their own system-bar insets, exactly like the normal scaffold.
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        if (useRail) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = OledBlack
            ) {
                screens.forEach { screen ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = {
                            // Re-tap the active tab → reset that screen to top.
                            if (selected) TabReselectBus.reselected(screen.route)
                            else onNavigate(screen.route)
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = {
                            Text(screen.title, style = MaterialTheme.typography.labelSmall)
                        },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = TealAccent,
                            selectedTextColor = TealAccent,
                            unselectedIconColor = DisabledGrey,
                            unselectedTextColor = DisabledGrey,
                            indicatorColor = OledBlack
                        )
                    )
                }
            }
        } else {
            NavigationBar(containerColor = OledBlack) {
                screens.forEach { screen ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // Tapping the already-active tab again resets that
                            // screen to its top level (Cloud → provider picker).
                            if (selected) TabReselectBus.reselected(screen.route)
                            else onNavigate(screen.route)
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = {
                            Text(screen.title, style = MaterialTheme.typography.labelSmall)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TealAccent,
                            selectedTextColor = TealAccent,
                            unselectedIconColor = DisabledGrey,
                            unselectedTextColor = DisabledGrey,
                            indicatorColor = OledBlack
                        )
                    )
                }
            }
        }
    }
}
