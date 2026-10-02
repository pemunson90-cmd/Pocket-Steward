package com.pocketsteward.app.ui.scan

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileScope
import com.pocketsteward.app.filing.*
import com.pocketsteward.app.navigation.TabEnvironment
import com.pocketsteward.app.navigation.TabTestApplication
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.SourcePrecondition
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.ui.theme.PocketStewardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w900dp-h900dp", application = TabTestApplication::class, shadows = [TabEnvironment::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilingReviewUiTest {
    @get:Rule val compose = createComposeRule()

    private fun review(): ScanViewModel {
        val app = ApplicationProvider.getApplicationContext<PocketStewardApplication>()
        val root = File(app.cacheDir, "filing-review").apply { mkdirs() }
        val inbox = File(root, "Download").apply { mkdirs() }
        val homeDir = File(root, "Documents/Lilith").apply { mkdirs() }
        val home = ProjectHomeCandidate("Lilith", homeDir.absolutePath)
        val decisions = listOf("notes.txt", "draft.txt").mapIndexed { n, name ->
            val file = File(inbox, name).apply { writeText("a") }
            FilingDecision(FilingArtifact(file.absolutePath, name, "txt", file.length(), modifiedAt = file.lastModified(), parentRef = inbox.absolutePath),
                home.name, home, null, home.path, if (n == 0) FilingConfidence.STRONG else FilingConfidence.PROBABLE, emptyList())
        }
        val result = InboxFilingResult(decisions)
        val plan = InboxFilingPlanAdapter.build(result, FileRef.Direct(root.absolutePath), setOf(home.path))
        runBlocking {
            app.container.settingsRepository.setStorageAccessMode(StorageAccessMode.DIRECT)
            val records = (listOf(inbox) + decisions.map { File(it.artifact.stableRef) }).map { file ->
                FileRecord(stableRef = file.absolutePath, displayName = file.name, extension = file.extension, mimeType = null,
                    absolutePathOrUri = file.absolutePath, parentRef = file.parent, sizeBytes = file.length(), createdAt = null,
                    modifiedAt = file.lastModified(), lastScannedAt = 1, isDirectory = file.isDirectory, isHidden = false)
            }
            app.container.database.fileRecordDao().upsertAll(records)
            app.container.database.fileRecordDao().insertScopeTags(records.map { FileScope(it.stableRef, inbox.absolutePath) })
        }
        lateinit var vm: ScanViewModel
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ScanViewModel(app.container.settingsRepository, app.container)
            val baselines = decisions.associate { it.artifact.stableRef to SourcePrecondition(1, it.artifact.modifiedAt) }
            vm.filingSession = FilingSession(result, FileRef.Direct(root.absolutePath), setOf(home.path), emptySet(), listOf(home),
                reviewId = "review", originalSources = baselines)
            vm._preview.value = ScanUiState.PlanPreview("Inbox filing", plan.operations, emptyList(), listOf(ScanScope("Downloads", FileRef.Direct(inbox.absolutePath))),
                List(plan.operations.size) { "Downloads" }, selectedIndices = setOf(0), reviewedSources = baselines,
                filingPresentation = plan.presentation.copy(reviewSessionId = "review"), storageMode = StorageAccessMode.DIRECT,
                authorizedDestinationRoots = plan.authorizedDestinationRoots)
        }
        compose.setContent { PocketStewardTheme { PlanPreviewScreen(vm, {}) } }
        compose.waitForIdle()
        return vm
    }

    @Test fun keepRemainingEnablesRunAndReselectionRescindsTheKeepChoice() {
        val vm = review()
        compose.onNodeWithText("Run 1").assertIsNotEnabled()
        compose.onNodeWithText("Keep 1 remaining here").performClick()
        compose.onNodeWithText("Run 1").assertIsEnabled()
        val probableRef = vm.filingSession!!.result.decisions.last().artifact.stableRef
        compose.runOnIdle {
            assertThat(vm.preview.value!!.selectedIndices).containsExactly(0)
            assertThat(vm.preview.value!!.filingPresentation!!.heldSourceRefs).containsExactly(probableRef)
            vm.setPlanOperationSelected(1, true)
            vm.setPlanOperationSelected(1, false)
        }
        compose.onNodeWithText("Run 1").assertIsNotEnabled()
    }

    @Test fun incompleteReviewCannotEnqueueEvenWhenApprovalIsCalledDirectly() {
        val vm = review()
        val latestBefore = runBlocking { vm.container.database.taskRunDao().latestTaskId() }
        compose.runOnIdle { vm.approvePlan(vm.preview.value!!) }
        compose.waitUntil(10_000) { vm.error.value?.contains("still need a decision") == true }
        assertThat(runBlocking { vm.container.database.taskRunDao().latestTaskId() }).isEqualTo(latestBefore)
    }

    @Test fun explicitDeferRebuildsCheckpointMoveWhilePreservingSelectedProjectAndBaselines() {
        val vm = review()
        val before = vm.preview.value!!
        compose.onNodeWithText("Send 1 remaining to Uncertain").performClick()
        assertThat(vm.filingEditJob).isNotNull()
        try {
            compose.waitUntil(10_000) {
                // Robolectric's paused looper needs to deliver background coroutine returns.
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                (vm.preview.value?.filingPresentation?.checkpointCount == 1 && !vm.hasActiveFilingWork) || vm.error.value != null
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("checkpoint=${vm.preview.value?.filingPresentation?.checkpointCount}, active=${vm.hasActiveFilingWork}, error=${vm.error.value}, state=${vm._uiState.value}, busy=${vm.busy.value}", error)
        }
        assertThat(vm.error.value).isNull()
        val after = vm.preview.value!!
        assertThat(after.reviewedSources).isEqualTo(before.reviewedSources)
        val moves = after.selectedIndices.mapNotNull { after.accepted[it] as? PlannedOperation.Move }
        assertThat(moves).hasSize(2)
        assertThat(moves.first { it.source.rawValue().endsWith("/draft.txt") }.destination.rawValue()).contains("/Download/Uncertain/draft.txt")
        assertThat(moves.first { it.source.rawValue().endsWith("/notes.txt") }.destination.rawValue()).contains("/Documents/Lilith/notes.txt")
        compose.onNodeWithText("Run 2").assertIsEnabled()
    }
}
