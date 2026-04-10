package com.powermediaplayer.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
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
    data object Equalizer : Screen("equalizer", "EQ", Icons.Filled.Equalizer)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

private val screens = listOf(Screen.Player, Screen.Library, Screen.Equalizer, Screen.Settings)

/**
 * Main app navigation. Hosts a SHARED LibraryViewModel across the Library tab
 * so that the "navigate to player" action can trigger playback and switch tabs
 * atomically.
 */
@Composable
fun AppNavigation(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

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

    Scaffold(
        containerColor = OledBlack,
        bottomBar = {
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Player.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Player.route) {
                PlayerScreen(windowSizeClass = windowSizeClass)
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onNavigateToPlayer = navigateToPlayer
                )
            }
            composable(Screen.Equalizer.route) { EqualizerScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
