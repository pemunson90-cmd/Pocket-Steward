package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import org.junit.Test

class FilingInventoryTest {
    private val root = FileRef.Direct("/storage/emulated/0")
    private val inbox = "${root.absolutePath}/Download"
    private val home = ProjectHomeCandidate("Lilith", "${root.absolutePath}/Documents/Lilith")
    private fun decision(name: String, confidence: FilingConfidence = FilingConfidence.PROBABLE, checkpoint: Boolean = false) = FilingDecision(
        FilingArtifact("$inbox/${if (checkpoint) "Uncertain/" else ""}$name", name, "txt", 10, modifiedAt = 1,
            parentRef = if (checkpoint) "$inbox/Uncertain" else inbox),
        "Lilith", home, null, home.path, confidence, emptyList())

    @Test fun uncheckedProbableNeedsDecisionAndNeverGetsAnImplicitCheckpointMove() {
        val source = decision("draft.txt")
        val plan = InboxFilingPlanAdapter.build(InboxFilingResult(listOf(source)), root, setOf(home.path))
        val inventory = FilingInventoryPolicy.build(plan.presentation, plan.operations, emptySet(), emptyList())
        assertThat(inventory.complete).isFalse()
        assertThat(inventory.pendingRefs).containsExactly(source.artifact.stableRef)
        assertThat(plan.operations.filterIsInstance<PlannedOperation.Move>().single().destination.rawValue()).isEqualTo("${home.path}/draft.txt")
    }

    @Test fun explicitKeepCompletesInventoryWithoutSelectingAnOperation() {
        val source = decision("draft.txt")
        val plan = InboxFilingPlanAdapter.build(InboxFilingResult(listOf(source)), root, setOf(home.path))
        val inventory = FilingInventoryPolicy.build(plan.presentation.copy(heldSourceRefs = setOf(source.artifact.stableRef)), plan.operations, emptySet(), emptyList())
        assertThat(inventory.complete).isTrue()
        assertThat(inventory.count(FilingOutcome.KEEP)).isEqualTo(1)
    }

    @Test fun explicitDeferChangesOnlyChosenSourcesAndPreservesChoiceAcrossEvidenceContinuation() {
        val chosen = decision("draft.txt")
        val other = decision("notes.txt", FilingConfidence.STRONG)
        val original = InboxFilingResult(listOf(chosen, other))
        val deferred = FilingDispositionPolicy.defer(original, setOf(chosen.artifact.stableRef))
        val resumed = FilingContinuationPolicy.applyAssignments(original, mapOf(chosen.artifact.stableRef to deferred.decisions.first()))
        assertThat(resumed.decisions.last()).isEqualTo(other)
        val plan = InboxFilingPlanAdapter.build(resumed, root, setOf(home.path, "$inbox/Uncertain"))
        val moves = plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves.first { it.source.rawValue() == chosen.artifact.stableRef }.destination.rawValue()).isEqualTo("$inbox/Uncertain/draft.txt")
        val inventory = FilingInventoryPolicy.build(plan.presentation, plan.operations, plan.operations.indices.toSet(), emptyList())
        assertThat(inventory.count(FilingOutcome.CHECKPOINT)).isEqualTo(1)
        assertThat(inventory.count(FilingOutcome.DESTINATION)).isEqualTo(1)
        assertThat(inventory.complete).isTrue()
    }

    @Test fun deferredExistingCheckpointItemIsRetainedWithoutNestedCheckpoint() {
        val source = decision("draft.txt", checkpoint = true)
        val result = FilingDispositionPolicy.defer(InboxFilingResult(listOf(source)), setOf(source.artifact.stableRef))
        val plan = InboxFilingPlanAdapter.build(result, root, setOf(home.path), setOf(source.artifact.stableRef))
        assertThat(plan.operations).isEmpty()
        val inventory = FilingInventoryPolicy.build(plan.presentation, emptyList(), emptySet(), emptyList())
        assertThat(inventory.count(FilingOutcome.RETAINED_UNCERTAIN)).isEqualTo(1)
        assertThat(inventory.complete).isTrue()
    }

    @Test fun deselectingCheckpointMoveRequiresExplicitKeep() {
        val original = decision("draft.txt")
        val result = FilingDispositionPolicy.defer(InboxFilingResult(listOf(original)), setOf(original.artifact.stableRef))
        val plan = InboxFilingPlanAdapter.build(result, root, setOf("$inbox/Uncertain"))
        val inventory = FilingInventoryPolicy.build(plan.presentation, plan.operations, emptySet(), emptyList())
        assertThat(inventory.pendingRefs).containsExactly(original.artifact.stableRef)
    }

    @Test fun blockedMoveRetainsTheExactValidatorReason() {
        val original = decision("draft.txt")
        val plan = InboxFilingPlanAdapter.build(InboxFilingResult(listOf(original)), root, setOf(home.path))
        val rejected = RejectedOperation(plan.operations.single(), "Destination already exists.")
        val inventory = FilingInventoryPolicy.build(plan.presentation, emptyList(), emptySet(), listOf(rejected))
        assertThat(inventory.entries.single().reason).isEqualTo(rejected.reason)
        assertThat(inventory.count(FilingOutcome.BLOCKED)).isEqualTo(1)
        assertThat(inventory.complete).isTrue()
    }

    @Test fun copyDoesNotClaimTheOriginalLeavesDownloads() {
        val source = decision("draft.txt")
        val plan = InboxFilingPlanAdapter.build(InboxFilingResult(listOf(source)), root, setOf(home.path))
        val move = plan.operations.single() as PlannedOperation.Move
        val copy = PlannedOperation.Copy(move.source, move.destination, "Keep original")
        val inventory = FilingInventoryPolicy.build(plan.presentation, listOf(copy), setOf(0), emptyList())
        assertThat(inventory.count(FilingOutcome.DESTINATION)).isEqualTo(0)
        assertThat(inventory.count(FilingOutcome.COPY_RETAINED)).isEqualTo(1)
        assertThat(inventory.entries.single().reason).contains("original stays here")
    }

    @Test fun sixteenThousandSourcesHaveExactlyOneOutcomeIncludingCheckpointPresentationDuplicates() {
        val decisions = List(16_000) { n -> decision("$n.txt", if (n % 2 == 0) FilingConfidence.STRONG else FilingConfidence.PROBABLE) }
        val result = FilingDispositionPolicy.defer(InboxFilingResult(decisions), decisions.take(4_000).mapTo(hashSetOf()) { it.artifact.stableRef })
        val plan = InboxFilingPlanAdapter.build(result, root, setOf(home.path, "$inbox/Uncertain"))
        val selected = plan.operations.mapIndexedNotNull { index, op ->
            index.takeIf { op is PlannedOperation.Move && op.source.rawValue() in plan.defaultSelectedSourceRefs }
        }.toSet()
        val inventory = FilingInventoryPolicy.build(plan.presentation, plan.operations, selected, emptyList())
        assertThat(inventory.entries.map { it.item.sourceRef }.distinct()).hasSize(16_000)
        assertThat(inventory.count(FilingOutcome.CHECKPOINT)).isEqualTo(4_000)
        assertThat(inventory.count(FilingOutcome.DESTINATION)).isEqualTo(6_000)
        assertThat(inventory.count(FilingOutcome.NEEDS_DECISION)).isEqualTo(6_000)
        assertThat(FilingOutcome.entries.sumOf(inventory::count)).isEqualTo(16_000)
    }

    @Test fun invalidChoicesAndDuplicateSourcesAreRejectedBeforePlanning() {
        val result = InboxFilingResult(listOf(decision("a.txt")))
        assertThat(runCatching { FilingDispositionPolicy.defer(result, setOf("/foreign/a.txt")) }.isFailure).isTrue()
        assertThat(runCatching { InboxFilingPlanAdapter.build(InboxFilingResult(result.decisions + result.decisions), root, emptySet()) }.isFailure).isTrue()
    }
}
