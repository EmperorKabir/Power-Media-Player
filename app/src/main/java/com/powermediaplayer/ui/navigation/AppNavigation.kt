package com.powermediaplayer.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
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
}

private val screens = listOf(
    Screen.Player, Screen.Library, Screen.LastPlayed,
    Screen.Cloud, Screen.Equalizer, Screen.Settings
)

/** Height of the immersive-video app-tab overlay, excluding the system nav
 *  inset (the bar adds that itself). The video transport stack reserves this
 *  much bottom space while the overlay is shown so the two never collide. */
internal val ImmersiveVideoTabBarHeight = 56.dp

/**
 * Main app navigation. Hosts a SHARED LibraryViewModel across the Library tab
 * so that the "navigate to player" action can trigger playback and switch tabs
 * atomically.
 */
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

    // Audit 6.1 / 8.1 — bar↔rail by window width. Phones keep the bottom
    // bar; tablets/unfolded foldables get a NavigationRail (Play
    // large-screen tier requirement). Immersive video hides navigation
    // entirely.
    val navLayoutType = when {
        com.powermediaplayer.MainActivityHolder.fullBleedVideo.value ->
            androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.None
        windowSizeClass.widthSizeClass == androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact ->
            androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationBar
        else ->
            androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationRail
    }
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
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Player.route
        ) {
            composable(Screen.Player.route) {
                PlayerScreen(
                    windowSizeClass = windowSizeClass,
                    adaptive = com.powermediaplayer.ui.adaptive.rememberAdaptiveInfo(windowSizeClass),
                    onNavigateToLibrary = navigateToLibrary
                )
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onNavigateToPlayer = navigateToPlayer
                )
            }
            composable(Screen.Cloud.route) {
                CloudBrowserScreen(onNavigateToPlayer = navigateToPlayer)
            }
            composable(Screen.LastPlayed.route) {
                com.powermediaplayer.ui.lastplayed.LastPlayedScreen(
                    onNavigateToPlayer = navigateToPlayer
                )
            }
            composable(Screen.Equalizer.route) { EqualizerScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(windowSizeClass = windowSizeClass)
            }
        }
        // In-app picture-in-picture: keep the video visible while the
        // user browses other tabs. Hidden on the Player tab (the full
        // surface owns the video there); system PiP on leaving the app
        // is unchanged (MainActivity's PiP branch).
        if (!isPlayerRoute) {
            com.powermediaplayer.ui.components.FloatingVideoMiniPlayer(
                onExpand = navigateToPlayer
            )
        }
        }
        // MiniPlayerBar — every non-Player tab; spans the CONTENT width
        // so it sits beside the rail on wide layouts rather than under it.
        if (!isPlayerRoute) {
            com.powermediaplayer.ui.components.MiniPlayerBar(
                onClick = navigateToPlayer
            )
        }
        }
        // Immersive-video app-tab overlay — floats over the bottom of the
        // full-bleed picture when controls are up, so switching tabs never
        // resizes the video. The normal NSS bar is None while immersive.
        ImmersiveVideoTabOverlay(
            visible = com.powermediaplayer.MainActivityHolder.fullBleedVideo.value &&
                com.powermediaplayer.MainActivityHolder.videoControlsVisible.value,
            currentDestination = currentDestination,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        }
    }
}

/**
 * Slim app-tab bar overlaid on the bottom of a full-bleed video while the
 * controls are visible. It floats ON TOP of the picture (never a layout
 * sibling) so showing/hiding tabs can't resize the video. Icons only, to
 * stay compact; the active tab is tinted. Adds the system-nav inset itself.
 */
@Composable
private fun ImmersiveVideoTabOverlay(
    visible: Boolean,
    currentDestination: NavDestination?,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OledBlack.copy(alpha = 0.92f))
                .navigationBarsPadding()
                .height(ImmersiveVideoTabBarHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            screens.forEach { screen ->
                val selected =
                    currentDestination?.hierarchy?.any { it.route == screen.route } == true
                IconButton(onClick = { onNavigate(screen.route) }) {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.title,
                        tint = if (selected) TealAccent else DisabledGrey
                    )
                }
            }
        }
    }
}
