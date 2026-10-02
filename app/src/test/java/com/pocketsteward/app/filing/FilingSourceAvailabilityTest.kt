package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class FilingSourceAvailabilityTest {
    private fun result() = InboxFilingResult(listOf("missing.txt", "available.txt").map { name ->
        val home = ProjectHomeCandidate("Lilith", "/sd/Documents/Lilith")
        FilingDecision(FilingArtifact("/sd/Download/$name", name, "txt", 12, modifiedAt = 1, parentRef = "/sd/Download"),
            "Lilith", home, null, home.path, FilingConfidence.STRONG, listOf(FilingEvidence(FilingEvidenceKind.FILENAME, "Lilith", 120)))
    })
    @Test fun unavailableSourceIsVisibleAndBlockedWhileOtherSourcesRemainRunnable() {
        val result = FilingSourceAvailability.block(result(), mapOf("/sd/Download/missing.txt" to "Restore access"))
        val plan = InboxFilingPlanAdapter.build(result, FileRef.Direct("/sd"), setOf("/sd/Documents/Lilith"))
        val moves = plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves.size).isEqualTo(1)
        assertThat(moves.single().source).isEqualTo(FileRef.Direct("/sd/Download/available.txt"))
        assertThat(plan.presentation.unresolved.map { it.sourceRef }).containsExactly("/sd/Download/missing.txt")
        assertThat(plan.presentation.checkpointCount).isEqualTo(0)
        val inventory = FilingInventoryPolicy.build(plan.presentation, plan.operations, plan.operations.indices.toSet(), emptyList())
        assertThat(inventory.count(FilingOutcome.BLOCKED)).isEqualTo(1)
        assertThat(inventory.complete).isTrue()
        assertThat(inventory.entries.first { it.outcome == FilingOutcome.BLOCKED }.reason).isEqualTo("Restore access")
    }
    @Test fun savedAssignmentCannotRestoreMoveAuthorityToUnavailableSource() {
        val blocked = FilingSourceAvailability.block(result(), mapOf("/sd/Download/missing.txt" to "Restore access"))
        val assigned = FilingContinuationPolicy.applyAssignments(blocked, mapOf("/sd/Download/missing.txt" to result().decisions.first()))
        val plan = InboxFilingPlanAdapter.build(assigned, FileRef.Direct("/sd"), emptySet())
        assertThat(plan.operations.filterIsInstance<PlannedOperation.Move>().map { it.source }).doesNotContain(FileRef.Direct("/sd/Download/missing.txt"))
        assertThat(plan.presentation.unresolved.map { it.sourceRef }).contains("/sd/Download/missing.txt")
    }
    @Test fun selectedFolderAdapterAlsoKeepsUnavailableSourcesOutOfCheckpoint() {
        val source = "content://provider/tree/root/document/missing"
        val missing = result().decisions.first().copy(artifact = result().decisions.first().artifact.copy(stableRef = source, parentRef = "content://provider/tree/root/document/root"))
        val blocked = FilingSourceAvailability.block(InboxFilingResult(listOf(missing)), mapOf(source to "Restore access"))
        val plan = InboxFilingSafPlanAdapter.build(blocked, FileRef.Saf("content://provider/tree/root/document/root"), emptyMap(), "Inbox")
        assertThat(plan.operations).isEmpty()
        assertThat(plan.presentation.blockedSourceReasons).containsExactly(source, "Restore access")
    }
}
