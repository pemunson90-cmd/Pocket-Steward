package com.pocketsteward.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketsteward.app.ui.history.HistoryScreen
import com.pocketsteward.app.ui.home.HomeScreen
import com.pocketsteward.app.ui.onboarding.OnboardingScreen
import com.pocketsteward.app.ui.scan.PostScanAction
import com.pocketsteward.app.ui.scan.ScanFlow
import com.pocketsteward.app.ui.scan.scanFlowGraph
import com.pocketsteward.app.ui.settings.SettingsScreen
import com.pocketsteward.app.ui.trash.TrashScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val TRASH = "trash"

    /**
     * The scan flow is a nested graph now, not a destination
     * ([com.pocketsteward.app.ui.scan.ScanFlow]). Home enters it at its start
     * destination; everything inside it has its own route and its own back.
     */
    const val SCAN_FLOW = ScanFlow.GRAPH

    fun scanFlowWith(action: PostScanAction): String = ScanFlow.entryWith(action)
    fun scanFlowWithRequest(request: String): String = ScanFlow.entryWithRequest(request)
    fun scanFlowWithWorkflow(workflowId: String): String = ScanFlow.entryWithWorkflow(workflowId)
    fun scanFlowWithSavedSearch(searchId: String): String = ScanFlow.entryWithSavedSearch(searchId)
    fun scanFlowWithImportedPlan(cachePath: String): String = ScanFlow.entryWithImportedPlan(cachePath)
}

@Composable
fun PocketStewardNavHost(
    startDestination: String,
    postOnboardingDestination: String = Routes.HOME,
    navController: NavHostController = rememberNavController(),
) {
    PocketStewardShell(navController) { shellModifier ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = shellModifier,
        ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onAccessGranted = {
                    navController.navigate(postOnboardingDestination) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onExplore = { navController.navigate(Routes.SCAN_FLOW) },
                onOpenTasks = { navController.navigate(Routes.HISTORY) },
                onNaturalLanguageRequest = { request -> navController.navigate(Routes.scanFlowWithRequest(request)) },
                onSavedWorkflow = { workflowId -> navController.navigate(Routes.scanFlowWithWorkflow(workflowId)) },
                onSavedSearch = { searchId -> navController.navigate(Routes.scanFlowWithSavedSearch(searchId)) },
                onImportedPlan = { cachePath -> navController.navigate(Routes.scanFlowWithImportedPlan(cachePath)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
            )
        }
        scanFlowGraph(
            navController = navController,
            // Back out of the flow's first destination leaves the flow
            // entirely, which is when the graph's ViewModel — and with it the
            // scan index — is genuinely no longer wanted.
            onExitFlow = { navController.popBackStack() },
        )
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }
        }
    }
}
