package com.pocketsteward.app.navigation

import com.pocketsteward.app.ui.scan.PostScanAction
import java.util.Locale

/** Launcher requests choose a review workflow, never an executor action. */
object LauncherEntryPolicy {
    fun destination(scheme: String?, host: String?): String {
        if (!scheme.equals("pocketsteward", ignoreCase = true)) return Routes.FILES
        return when (host?.lowercase(Locale.ROOT)) {
            "organize" -> Routes.scanFlowWith(PostScanAction.INBOX_FILING)
            "sort", "uncertain" -> Routes.scanFlowWith(PostScanAction.UNCERTAIN_FILING)
            "explore", "search" -> Routes.SCAN_FLOW
            "tasks" -> Routes.HISTORY
            "scheduled" -> Routes.scanFlowScheduledReview()
            "home" -> Routes.HOME
            else -> Routes.FILES
        }
    }
}
