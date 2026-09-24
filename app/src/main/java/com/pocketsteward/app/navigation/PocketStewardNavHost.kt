package com.pocketsteward.app.navigation

import com.pocketsteward.app.ui.browser.BrowserScreen
import com.pocketsteward.app.ui.ask.AskScreen
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
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
import com.pocketsteward.app.ui.scan.ScanFlow
import com.pocketsteward.app.ui.scan.scanFlowGraph
import com.pocketsteward.app.ui.settings.SettingsScreen
import com.pocketsteward.app.ui.trash.TrashScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val HISTORY_TASK = "history/task/{taskId}"
    const val TRASH = "trash"
    const val ASK = "ask"
    const val FILES = "files"

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
    fun scanFlowScheduledReview(): String = ScanFlow.entryWithScheduledReview()
    fun scanFlowLastScan(): String = ScanFlow.entryWithLastScan()
    fun historyTask(taskId: Long): String = "history/task/$taskId"
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
            // Material "shared axis X": forward slides in from the right,
            // back from the left, both short and faded. Enough to show which
            // way you went; short enough never to feel like waiting.
            enterTransition = {
                fadeIn(tween(220, delayMillis = 60)) +
                    slideInHorizontally(tween(280)) { it / 10 }
            },
            exitTransition = {
                fadeOut(tween(120)) + slideOutHorizontally(tween(280)) { -it / 10 }
            },
            popEnterTransition = {
                fadeIn(tween(220, delayMillis = 60)) +
                    slideInHorizontally(tween(280)) { -it / 10 }
            },
            popExitTransition = {
                fadeOut(tween(120)) + slideOutHorizontally(tween(280)) { it / 10 }
            },
        ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onAccessGranted = {
                    // Files is a tab: Home stays underneath it as the base of
                    // the back stack, the way every tabbed app behaves.
                    val first = if (postOnboardingDestination == Routes.FILES) Routes.HOME else postOnboardingDestination
                    navController.navigate(first) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                    if (postOnboardingDestination == Routes.FILES) navController.navigateTopLevel(Routes.FILES)
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onExplore = { navController.navigate(Routes.SCAN_FLOW) },
                onContinueLastScan = { navController.navigate(Routes.scanFlowLastScan()) },
                onOpenTasks = { navController.navigate(Routes.HISTORY) },
                onAsk = { navController.navigate(Routes.ASK) },
                onNaturalLanguageRequest = { request -> navController.navigate(Routes.scanFlowWithRequest(request)) },
                onQuickAction = { action -> navController.navigate(Routes.scanFlowWith(action)) },
                onScheduledReview = { navController.navigate(Routes.scanFlowScheduledReview()) },
                onSavedWorkflow = { workflowId -> navController.navigate(Routes.scanFlowWithWorkflow(workflowId)) },
                onSavedSearch = { searchId -> navController.navigate(Routes.scanFlowWithSavedSearch(searchId)) },
                onImportedPlan = { cachePath -> navController.navigate(Routes.scanFlowWithImportedPlan(cachePath)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onChangeStorageAccess = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
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
        composable(
            route = Routes.HISTORY_TASK,
            arguments = listOf(
                navArgument("taskId") { type = NavType.LongType },
            ),
        ) { entry ->
            HistoryScreen(
                onBack = { navController.popBackStack() },
                initialManifestTaskId = entry.arguments?.getLong("taskId"),
            )
        }
        composable(Routes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.FILES) {
            BrowserScreen()
        }
        composable(Routes.ASK) {
            AskScreen(onBack = { navController.popBackStack() })
        }
        }
    }
}
