package com.powermediaplayer.ui.navigation

import androidx.compose.foundation.layout.padding
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

/**
 * Main app navigation. Hosts a SHARED LibraryViewModel across the Library tab
 * so that the "navigate to player" action can trigger playback and switch tabs
 * atomically.
 */
@Composable
fun AppNavigation(
    windowSizeClass: WindowSizeClass,
    initialOpenTab: String? = null
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // §C20 — handle widget tap deep-link. When the widget host launched
    // us with EXTRA_OPEN_TAB="player" (the only value we currently
    // surface), force-navigate to the Player route. Trigger keyed on
    // the value so a fresh tap re-fires even if the user had moved off
    // the route.
    androidx.compose.runtime.LaunchedEffect(initialOpenTab) {
        when (initialOpenTab) {
            "player" -> navController.navigate(Screen.Player.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Shared ViewModel scoped to the NavGraph host — allows LibraryScreen to
    // trigger playback and then navigate to the Player tab in one tap.
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val navigateToPlayer = {
        navController.navigate(Screen.Player.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
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

    Scaffold(
        containerColor = OledBlack,
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                // MiniPlayerBar — visible on every non-Player tab.
                // Tapping the bar navigates to the Player tab.
                if (!isPlayerRoute) {
                    com.powermediaplayer.ui.components.MiniPlayerBar(
                        onClick = navigateToPlayer
                    )
                }
                NavigationBar(
                    containerColor = OledBlack,
                    contentColor = TealAccent
                ) {
                screens.forEach { screen ->
                    NavigationBarItem(
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Player.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Player.route) {
                PlayerScreen(
                    windowSizeClass = windowSizeClass,
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
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
