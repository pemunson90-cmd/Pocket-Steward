package com.pocketsteward.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.pocketsteward.app.ui.scan.ScanFlow

private data class AppDestination(
    val label: String,
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val drawable: Int? = null,
)

@Composable
private fun AppDestination.Glyph() {
    if (icon != null) {
        Icon(icon, contentDescription = label)
    } else if (drawable != null) {
        Icon(androidx.compose.ui.res.painterResource(drawable), contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

private val appDestinations = listOf(
    AppDestination("Files", Routes.FILES, drawable = com.pocketsteward.app.R.drawable.ic_notification),
    AppDestination("Home", Routes.HOME, Icons.Default.Home),
    AppDestination("Explore", Routes.SCAN_FLOW, Icons.Default.Search),
    AppDestination("Tasks", Routes.HISTORY, Icons.Default.Menu),
    AppDestination("Settings", Routes.SETTINGS, Icons.Default.Settings),
)

@Composable
fun PocketStewardShell(
    navController: NavHostController,
    content: @Composable (Modifier) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val showNavigation = current != null && current.route != Routes.ONBOARDING

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = AdaptiveLayoutPolicy.useExpandedNavigation(maxWidth.value)

        if (showNavigation && expanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    appDestinations.forEach { destination ->
                        NavigationRailItem(
                            selected = destination.matches(current),
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = { destination.Glyph() },
                            label = { Text(destination.label) },
                        )
                    }
                }

                Scaffold(modifier = Modifier.weight(1f)) { padding ->
                    ShellContentFrame(current, padding, content)
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    if (showNavigation) {
                        NavigationBar {
                            appDestinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = destination.matches(current),
                                    onClick = { navController.navigateTopLevel(destination.route) },
                                    icon = { destination.Glyph() },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                ShellContentFrame(current, padding, content)
            }
        }
    }
}

internal fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Routes.HOME) { saveState = true }
    }
}

internal object AdaptiveLayoutPolicy {
    const val EXPANDED_NAV_MIN_WIDTH_DP = 600f
    const val TWO_PANE_MIN_WIDTH_DP = 520f

    fun useExpandedNavigation(widthDp: Float): Boolean =
        widthDp >= EXPANDED_NAV_MIN_WIDTH_DP

    fun useTwoPane(widthDp: Float): Boolean =
        widthDp >= TWO_PANE_MIN_WIDTH_DP
}

private fun AppDestination.matches(destination: NavDestination?): Boolean {
    if (destination == null) return false
    if (route == Routes.SCAN_FLOW) {
        return destination.hierarchy.any { it.route == ScanFlow.GRAPH }
    }
    if (route == Routes.SETTINGS && destination.route == Routes.TRASH) return true
    if (route == Routes.HISTORY &&
        destination.hierarchy.any { it.route == Routes.HISTORY_TASK }
    ) {
        return true
    }
    return destination.hierarchy.any { it.route == route }
}


@Composable
private fun ShellContentFrame(
    current: NavDestination?,
    padding: PaddingValues,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        val screen = if (current.usesWideWorkspace()) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .widthIn(max = 840.dp)
                .fillMaxHeight()
                .fillMaxWidth()
        }
        content(screen)
    }
}

private fun NavDestination?.usesWideWorkspace(): Boolean {
    if (this == null) return false
    return hierarchy.any { destination ->
        destination.route == Routes.FILES || destination.route == ScanFlow.GRAPH
    }
}
