package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import org.junit.Test

class FilingReleaseConventionTest {
    private fun result() = InboxFilingResult(listOf(FilingDecision(
        FilingArtifact("/Downloads/build.apk", "build.apk", "apk", 20, modifiedAt = 10, parentRef = "/Downloads"),
        "NSTL", ProjectHomeCandidate("NSTL", "/Documents/NSTL", hierarchy = ProjectHierarchyStrategy.VERSIONED),
        "1.2.3", "/Documents/NSTL/1.2.3", FilingConfidence.STRONG, emptyList(),
    )))
    @Test fun usesExistingVPrefixAndExactFolderSpelling() {
        val decision = FilingReleaseConvention.reconcile(result(), setOf("/Documents/NSTL/v1.2.3")).proposed.single()
        assertThat(decision.destinationDirectory).isEqualTo("/Documents/NSTL/v1.2.3")
        assertThat(decision.release).isEqualTo("v1.2.3")
    }
    @Test fun competingExistingConventionsStayUncertain() {
        val edited = FilingReleaseConvention.reconcile(result(), setOf("/Documents/NSTL/v1.2.3", "/Documents/NSTL/1.2.3"))
        assertThat(edited.proposed).isEmpty()
        assertThat(edited.unresolved.single().evidence.first().kind).isEqualTo(FilingEvidenceKind.DESTINATION_CONFLICT)
    }
    @Test fun anotherProjectAndNestedFoldersCannotRedirectRelease() {
        assertThat(FilingReleaseConvention.reconcile(result(), setOf("/Documents/Other/v1.2.3", "/Documents/NSTL/Archive/v1.2.3"))).isEqualTo(result())
    }
}
