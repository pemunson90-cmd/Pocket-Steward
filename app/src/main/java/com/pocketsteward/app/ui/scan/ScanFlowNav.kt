package com.pocketsteward.app.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.pocketsteward.app.PocketStewardApplication

/**
 * The scan flow as a real back stack.
 *
 * Every destination shares one [ScanViewModel], scoped to this nested graph's
 * own back stack entry rather than to each screen. That scoping is the whole
 * mechanism: it is what lets the user open a review, press back, and still
 * have the scan — `[device]` before M7, back from "Files older than 6 months"
 * discarded an index of 22,000 files and forced a full re-walk.
 *
 * The graph's ViewModel is cleared when the graph itself leaves the back
 * stack, which is exactly when the scan genuinely stops being wanted.
 */
object ScanFlow {
    const val GRAPH = "scan_flow"
    const val ARG_ACTION = "action"

    /** Entry route, optionally carrying a Home tile's [PostScanAction]. */
    const val ENTRY = "scan_flow/scan?$ARG_ACTION={$ARG_ACTION}"

    fun entryWith(action: PostScanAction): String = "scan_flow/scan?$ARG_ACTION=${action.name}"
}

fun NavGraphBuilder.scanFlowGraph(navController: NavHostController, onExitFlow: () -> Unit) {
    navigation(startDestination = ScanFlow.ENTRY, route = ScanFlow.GRAPH) {
        composable(
            route = ScanFlow.ENTRY,
            arguments = listOf(
                navArgument(ScanFlow.ARG_ACTION) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val viewModel = scanViewModel(navController)
            ScanFlowNavEffect(navController, viewModel)
            ScanScreen(
                viewModel = viewModel,
                autoAction = PostScanAction.fromRoute(entry.arguments?.getString(ScanFlow.ARG_ACTION)),
                onOpenPicker = { viewModel.browseFolders() },
                onBack = onExitFlow,
            )
        }

        composable(ScanRoute.PICKER.route) {
            val viewModel = scanViewModel(navController)
            ScanFlowNavEffect(navController, viewModel)
            FolderPickerScreen(viewModel = viewModel, onBack = { popFrom(navController, viewModel, ScanRoute.PICKER) })
        }

        composable(ScanRoute.RESULTS.route) {
            val viewModel = scanViewModel(navController)
            ScanFlowNavEffect(navController, viewModel)
            ResultsScreen(
                viewModel = viewModel,
                onBack = { popFrom(navController, viewModel, ScanRoute.RESULTS) },
                onScanAgain = {
                    viewModel.reset()
                    navController.popBackStack()
                },
            )
        }

        composable(ScanRoute.REVIEW.route) {
            val viewModel = scanViewModel(navController)
            ScanFlowNavEffect(navController, viewModel)
            ReviewScreen(viewModel = viewModel, onBack = { popFrom(navController, viewModel, ScanRoute.REVIEW) })
        }

        composable(ScanRoute.PREVIEW.route) {
            val viewModel = scanViewModel(navController)
            ScanFlowNavEffect(navController, viewModel)
            PlanPreviewScreen(viewModel = viewModel, onBack = { popFrom(navController, viewModel, ScanRoute.PREVIEW) })
        }

        composable(ScanRoute.COMPLETION.route) {
            val viewModel = scanViewModel(navController)
            ScanFlowNavEffect(navController, viewModel)
            CompletionScreen(
                viewModel = viewModel,
                onDone = {
                    // Done means the task is finished with, not that the scan
                    // is. Popping back to the results keeps the index and lets
                    // another action run against the same scope.
                    viewModel.onLeftDestination(ScanRoute.COMPLETION)
                    navController.popBackStack(ScanRoute.RESULTS.route, inclusive = false)
                },
            )
        }
    }
}

/**
 * One shared [ScanViewModel] for the whole graph.
 *
 * `viewModel()` with no owner would scope to the individual destination, so
 * each screen would get its own instance and its own empty scan — the exact
 * bug this milestone exists to fix, reintroduced by a defaulted parameter.
 */
@Composable
private fun scanViewModel(navController: NavHostController): ScanViewModel {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val graphEntry = remember(navController) { navController.getBackStackEntry(ScanFlow.GRAPH) }
    return viewModel(
        viewModelStoreOwner = graphEntry,
        factory = viewModelFactory {
            initializer { ScanViewModel(container.settingsRepository, container) }
        },
    )
}

/**
 * Turns the ViewModel's one-shot navigation events into actual navigation.
 *
 * `launchSingleTop` because a destination that re-emits its own route — a
 * second plan proposed from the same results, say — should replace rather than
 * stack. Without it, back would walk through every preview the user had ever
 * generated.
 */
@Composable
private fun ScanFlowNavEffect(navController: NavHostController, viewModel: ScanViewModel) {
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { route ->
            navController.navigate(route.route) { launchSingleTop = true }
        }
    }
}

private fun popFrom(navController: NavHostController, viewModel: ScanViewModel, route: ScanRoute) {
    viewModel.onLeftDestination(route)
    navController.popBackStack()
}
