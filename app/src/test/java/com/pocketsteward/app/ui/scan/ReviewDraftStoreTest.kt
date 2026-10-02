package com.pocketsteward.app.ui.scan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.filing.*
import com.pocketsteward.app.plan.*
import com.pocketsteward.app.storage.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ReviewDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun draft(): ReviewDraft {
        val source = FileRef.Saf("content://provider/document/CaseSensitive%3Aone")
        val root = FileRef.Saf("content://provider/tree/Root%3Aone")
        val destination = root.child("Lilith").child("Notes").child("a.txt")
        val operation = PlannedOperation.Copy(source, destination, "User assignment\nKeep original")
        val result = InboxFilingResult(listOf(FilingDecision(
            FilingArtifact(source.rawValue(), "a.txt", "txt", 12, modifiedAt = 45, parentRef = root.rawValue(), indexedText = "Lilith notes"),
            "Lilith", ProjectHomeCandidate("Lilith", "/Documents/Lilith"), null, "/Documents/Lilith/Notes",
            FilingConfidence.STRONG, listOf(FilingEvidence(FilingEvidenceKind.USER_MAPPING, "Assigned", 100)),
        )))
        return ReviewDraft(mode = StorageAccessMode.SAF, preview = ScanUiState.PlanPreview(
            "Organize\nLilith", listOf(operation), listOf(RejectedOperation(operation, "Example rejection")),
            listOf(ScanScope("Granted tree", root)), listOf("Granted tree"), selectedIndices = emptySet(),
            reviewedSources = mapOf(source.rawValue() to SourcePrecondition(12, 45, "digest", 1)),
            filingPresentation = FilingReviewPresentation(emptyList(), listOf(FilingReviewItem(source.rawValue(), "a.txt", 12, FilingConfidence.UNRESOLVED, listOf("Review"), null))),
            storageMode = StorageAccessMode.SAF, taskHistoryWatermark = 17,
        ), filingSession = FilingSession(result, root, emptySet(), setOf(source.rawValue()), emptyList(), mapOf("Lilith" to root.child("Lilith"))))
    }

    @Test fun restoresSelectionsTypedRefsAssignmentsAndOriginalBaselines() {
        val original = draft()
        assertThat(ReviewDraftCodec.decode(ReviewDraftCodec.encode(original))).isEqualTo(original)
    }
    @Test fun pendingLearningSurvivesRestartWithoutLearningUncheckedActions() {
        val original = draft()
        val operation = original.preview.accepted.single() as PlannedOperation.Copy
        val pending = com.pocketsteward.app.saved.PendingCorrection(com.pocketsteward.app.saved.CorrectionRule("Lilith", "Lilith", original.preview.scopeRoot.rawValue()),
            mapOf(operation.source.rawValue() to operation.destination.rawValue()))
        val updated = original.copy(preview = original.preview.copy(pendingCorrections = listOf(pending)))
        val restored = ReviewDraftCodec.decode(ReviewDraftCodec.encode(updated))!!
        assertThat(restored).isEqualTo(updated)
        assertThat(com.pocketsteward.app.saved.CorrectionApprovalPolicy.approved(restored.preview.pendingCorrections.orEmpty(), emptyList())).isEmpty()
    }

    @Test fun explicitKeepsRetainedCheckpointAndFolderCountsSurviveRestart() {
        val original = draft()
        val source = original.filingSession!!.result.decisions.single().artifact.stableRef
        val updated = original.copy(preview = original.preview.copy(filingPresentation = original.preview.filingPresentation!!.copy(
            heldSourceRefs = setOf(source), retainedUncertainSourceRefs = setOf(source), indexedFolderDescendantCount = 16_000)))
        val restored = ReviewDraftCodec.decode(ReviewDraftCodec.encode(updated))!!
        assertThat(restored).isEqualTo(updated)
        assertThat(FilingInventoryPolicy.build(restored.preview.filingPresentation!!, restored.preview.accepted,
            restored.preview.selectedIndices, restored.preview.rejected).count(FilingOutcome.KEEP)).isEqualTo(1)
    }

    @Test fun rejectsCorruptOrTruncatedDraftInsteadOfRecoveringPartialOperations() {
        val bytes = ReviewDraftCodec.encode(draft())
        assertThat(ReviewDraftCodec.decode(bytes.copyOf(bytes.size - 7))).isNull()
        bytes[bytes.lastIndex] = 'X'.code.toByte()
        assertThat(ReviewDraftCodec.decode(bytes)).isNull()
    }

    @Test fun cannotSaveOperationsWithoutReviewBaselines() {
        val original = draft()
        assertThat(runCatching { ReviewDraftCodec.encode(original.copy(preview = original.preview.copy(reviewedSources = emptyMap()))) }.isFailure).isTrue()
    }

    @Test fun cannotSaveOutOfBoundsSelectionsOrMismatchedStorageMode() {
        val original = draft()
        assertThat(runCatching { ReviewDraftCodec.encode(original.copy(preview = original.preview.copy(selectedIndices = setOf(2)))) }.isFailure).isTrue()
        assertThat(runCatching { ReviewDraftCodec.encode(original.copy(mode = StorageAccessMode.DIRECT)) }.isFailure).isTrue()
    }

    @Test fun atomicSaveKeepsPreviousDraftOnFailureAndExplicitClearRemovesIt() {
        val store = ReviewDraftStore(temporary.newFolder().resolve("draft"))
        val original = draft()
        store.save(original)
        runCatching { store.save(original.copy(version = 99)) }
        assertThat(store.load()).isEqualTo(original)
        store.save(null)
        assertThat(store.load()).isNull()
    }

    @Test fun sixteenThousandUnresolvedAssignmentsSurviveRestart() {
        val original = draft()
        val template = original.filingSession!!.result.decisions.single()
        val decisions = List(16_000) { n -> template.copy(artifact = template.artifact.copy(stableRef = "/Downloads/Uncertain/$n.txt", displayName = "$n.txt")) }
        val large = original.copy(filingSession = original.filingSession.copy(result = InboxFilingResult(decisions)))
        assertThat(ReviewDraftCodec.decode(ReviewDraftCodec.encode(large))).isEqualTo(large)
    }
    @Test fun staleWriterCannotRecreateClearedReview() {
        val store = ReviewDraftStore(temporary.newFolder().resolve("draft"))
        store.save(draft())
        store.save(null)
        store.save(draft()) { false }
        assertThat(store.load()).isNull()
    }

    @Test fun corruptPrivateFileIsDiscardedAndPendingFileIsNotRestored() {
        val directory = temporary.newFolder()
        directory.resolve("draft.pending").writeText("interrupted write")
        val file = directory.resolve("draft")
        val store = ReviewDraftStore(file)
        assertThat(store.load()).isNull()
        file.writeBytes(ReviewDraftCodec.encode(draft()).copyOf(90))
        assertThat(store.load()).isNull()
        assertThat(file.exists()).isFalse()
    }
    @Test fun queuedExactSelectionIsConsumedButOlderOrDifferentTasksAreNot() {
        val preview = draft().preview.copy(selectedIndices = setOf(0))
        assertThat(ReviewDraftPolicy.wasQueued(preview, listOf(18L to preview.accepted))).isTrue()
        assertThat(ReviewDraftPolicy.wasQueued(preview, listOf(17L to preview.accepted))).isFalse()
        assertThat(ReviewDraftPolicy.wasQueued(preview, listOf(18L to emptyList()))).isFalse()
        assertThat(ReviewDraftPolicy.wasQueued(preview.copy(selectedIndices = emptySet()), listOf(18L to preview.accepted))).isFalse()
    }
    @Test fun changedTreeGrantCannotRestoreOrApproveAnOldReview() {
        val preview = draft().preview.copy(storageGrantIdentity = "content://provider/tree/A")
        assertThat(ReviewDraftPolicy.hasCurrentAccess(preview, StorageAccessMode.SAF, "content://provider/tree/A")).isTrue()
        assertThat(ReviewDraftPolicy.hasCurrentAccess(preview, StorageAccessMode.SAF, "content://provider/tree/B")).isFalse()
        assertThat(ReviewDraftPolicy.hasCurrentAccess(preview, StorageAccessMode.DIRECT, null)).isFalse()
    }

    @Test fun crossedPreviewAndSessionRevisionsCannotBePersisted() {
        val original = draft()
        val session = original.filingSession!!.copy(reviewId = "next")
        assertThat(ReviewDraftPolicy.matchesFilingSession(original.preview, session)).isFalse()
        assertThat(runCatching { ReviewDraftCodec.encode(original.copy(filingSession = session)) }.isFailure).isTrue()
    }
    @Test fun continuationCoverageAssignmentsAndOriginalUnresolvedBaselinesSurviveRestart() {
        val original = draft()
        val session = original.filingSession!!.copy(reviewId = "same", manualAssignments = original.filingSession.result.decisions.associateBy { it.artifact.stableRef },
            originalSources = mapOf("unresolved" to SourcePrecondition(10, 100)))
        val preview = original.preview.copy(filingPresentation = original.preview.filingPresentation!!.copy(reviewSessionId = "same",
            imageCoverage = com.pocketsteward.app.image.ImageReviewCoverage(16_000, 500, 200, 40, 15_300, 15_960, 0, 0, textEnabled = true)))
        val current = original.copy(preview = preview, filingSession = session)
        assertThat(ReviewDraftCodec.decode(ReviewDraftCodec.encode(current))).isEqualTo(current)
    }

}
