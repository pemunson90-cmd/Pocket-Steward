package com.pocketsteward.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pocketsteward.app.ui.history.HistoryScreen
import com.pocketsteward.app.ui.home.HomeScreen
import com.pocketsteward.app.ui.onboarding.OnboardingScreen
import com.pocketsteward.app.ui.scan.PostScanAction
import com.pocketsteward.app.ui.scan.StorageScopeScreen
import com.pocketsteward.app.ui.settings.SettingsScreen
import com.pocketsteward.app.ui.trash.TrashScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val TRASH = "trash"

    /** Optional `action` picks a [PostScanAction] to run once the scan produces a summary. */
    const val STORAGE_SCOPE = "storage_scope"
    const val STORAGE_SCOPE_ARG_ACTION = "action"
    const val STORAGE_SCOPE_ROUTE = "$STORAGE_SCOPE?$STORAGE_SCOPE_ARG_ACTION={$STORAGE_SCOPE_ARG_ACTION}"

    fun storageScopeWith(action: PostScanAction): String =
        "$STORAGE_SCOPE?$STORAGE_SCOPE_ARG_ACTION=${action.name}"
}

@Composable
fun PocketStewardNavHost(startDestination: String, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onAccessGranted = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onScanStorage = { navController.navigate(Routes.STORAGE_SCOPE) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onQuickAction = { action -> navController.navigate(Routes.storageScopeWith(action)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
            )
        }
        composable(
            route = Routes.STORAGE_SCOPE_ROUTE,
            arguments = listOf(
                navArgument(Routes.STORAGE_SCOPE_ARG_ACTION) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            StorageScopeScreen(
                onBack = { navController.popBackStack() },
                autoAction = PostScanAction.fromRoute(
                    backStackEntry.arguments?.getString(Routes.STORAGE_SCOPE_ARG_ACTION),
                ),
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }
    }
}
