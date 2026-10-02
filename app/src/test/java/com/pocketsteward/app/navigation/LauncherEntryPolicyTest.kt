package com.pocketsteward.app.navigation

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.ui.scan.PostScanAction
import org.junit.Test

class LauncherEntryPolicyTest {
    @Test fun organizeAndSortRequestReviewedConfiguredInboxWorkflows() {
        assertThat(LauncherEntryPolicy.destination("pocketsteward", "organize")).isEqualTo(Routes.scanFlowWith(PostScanAction.INBOX_FILING))
        assertThat(LauncherEntryPolicy.destination("pocketsteward", "sort")).isEqualTo(Routes.scanFlowWith(PostScanAction.UNCERTAIN_FILING))
        assertThat(LauncherEntryPolicy.destination("POCKETSTEWARD", "UNCERTAIN")).isEqualTo(Routes.scanFlowWith(PostScanAction.UNCERTAIN_FILING))
    }
    @Test fun unknownOrForeignUriCannotChooseAnArbitraryWorkflow() {
        assertThat(LauncherEntryPolicy.destination("https", "organize")).isEqualTo(Routes.FILES)
        assertThat(LauncherEntryPolicy.destination("pocketsteward", "delete")).isEqualTo(Routes.FILES)
        assertThat(LauncherEntryPolicy.destination(null, null)).isEqualTo(Routes.FILES)
    }
    @Test fun previousEntryRoutesRemainAvailable() {
        assertThat(LauncherEntryPolicy.destination("pocketsteward", "tasks")).isEqualTo(Routes.HISTORY)
        assertThat(LauncherEntryPolicy.destination("pocketsteward", "explore")).isEqualTo(Routes.SCAN_FLOW)
        assertThat(LauncherEntryPolicy.destination("pocketsteward", "scheduled")).isEqualTo(Routes.scanFlowScheduledReview())
    }
}
