package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import org.junit.Test

class FilingContinuationPolicyTest {
    private val artifact = FilingArtifact("/Downloads/Uncertain/a.txt", "a.txt", "txt", 10, modifiedAt = 1, parentRef = "/Downloads/Uncertain", indexedText = "Old evidence")
    private val home = ProjectHomeCandidate("Lilith", "/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
    private val original = FilingDecision(artifact, null, null, null, null, FilingConfidence.UNRESOLVED, emptyList())
    @Test fun continuationPreservesExplicitReleaseAndRoleUsingOnlyFreshEvidence() {
        val assigned = FilingAssignments.assign(InboxFilingResult(listOf(original)), setOf(artifact.stableRef), home, FilingRole.NOTES, "1.2.3-dev4").decisions.single()
        val fresh = original.copy(artifact = artifact.copy(indexedText = "Fresh Lilith notes", imageText = "Observed text"),
            evidence = listOf(FilingEvidence(FilingEvidenceKind.INDEXED_CONTENT, "Fresh evidence", 70)))
        val result = FilingContinuationPolicy.applyAssignments(InboxFilingResult(listOf(fresh)), mapOf(artifact.stableRef to assigned)).decisions.single()
        assertThat(result.artifact).isEqualTo(fresh.artifact)
        assertThat(result.destinationDirectory).isEqualTo("/Documents/Lilith/Versions/1.2.3-dev4/Notes")
        assertThat(result.release).isEqualTo("1.2.3-dev4")
        assertThat(result.evidence.map { it.detail }).contains("Fresh evidence")
        assertThat(result.evidence.map { it.kind }).contains(FilingEvidenceKind.USER_MAPPING)
    }
    @Test fun continuationDoesNotReintroduceMissingSourcesOrExpandIntake() {
        val assigned = FilingAssignments.assign(InboxFilingResult(listOf(original)), setOf(artifact.stableRef), home, FilingRole.NOTES).decisions.single()
        assertThat(FilingContinuationPolicy.applyAssignments(InboxFilingResult(emptyList()), mapOf(artifact.stableRef to assigned)).decisions).isEmpty()
    }
    @Test fun existingDeselectionsSurviveAndNewlyStrongSourcesCanBeProposed() {
        assertThat(FilingContinuationPolicy.selectedSources(setOf("a", "b", "c"), setOf("a", "b"), setOf("b"))).containsExactly("b", "c")
    }
    @Test fun manuallyCheckedProbableSourcesSurviveEvenWhenNotDefaultSelected() {
        assertThat(FilingContinuationPolicy.selectedSources(emptySet(), setOf("a"), setOf("a"))).containsExactly("a")
    }
    @Test fun sixteenThousandChoicesRetainExactlyTheirSourceIdentity() {
        val all = (0 until 16_000).mapTo(hashSetOf()) { "source:$it" }
        val selected = all.filterTo(hashSetOf()) { it.removePrefix("source:").toInt() % 3 == 0 }
        assertThat(FilingContinuationPolicy.selectedSources(all, all, selected)).isEqualTo(selected)
    }
}
