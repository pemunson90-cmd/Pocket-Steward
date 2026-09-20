package com.pocketsteward.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.pocketsteward.app.ui.scan.ScanFlow

private data class AppDestination(
    val label: String,
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val appDestinations = listOf(
    AppDestination("Home", Routes.HOME, Icons.Default.Home),
    AppDestination("Explore", Routes.SCAN_FLOW, Icons.Default.Search),
    AppDestination("Tasks", Routes.HISTORY, Icons.Default.History),
    AppDestination("Settings", Routes.SETTINGS, Icons.Default.Settings),
)

@Composable
fun PocketStewardShell(
    navController: NavHostController,
    content: @Composable (Modifier) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val showNavigation = current?.route != Routes.ONBOARDING

    Scaffold(
        bottomBar = {
            if (showNavigation) {
                NavigationBar {
                    appDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = destination.matches(current),
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(Routes.HOME) { saveState = true }
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

private fun AppDestination.matches(destination: NavDestination?): Boolean {
    if (destination == null) return false
    if (route == Routes.SCAN_FLOW) {
        return destination.hierarchy.any { it.route == ScanFlow.GRAPH }
    }
    if (route == Routes.SETTINGS && destination.route == Routes.TRASH) return true
    return destination.hierarchy.any { it.route == route }
}
