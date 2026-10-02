package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import org.junit.Test

class FilingReleaseConventionTest {
    @Test fun apkArchiveAndHandoffNamesShareDevBuildIdentityWithoutContainerExtensions() {
        val versions = listOf("1.4.0-dev18", "2.0.0-rc.3-build17", "1.2.3+build.27", "3.0.0-beta2")
        for (version in versions) {
            for (extension in listOf("apk", "zip", "md")) {
                val name = "Pocket-Steward-$version.$extension"
                val artifact = FilingArtifact("/Downloads/$name", name, extension, 10, modifiedAt = 1, parentRef = "/Downloads")
                assertThat(InboxFilingEngine.releaseOf(artifact)).isEqualTo(version)
            }
        }
        val notes = FilingArtifact("/D/NSTL-1.2.3-build-notes.md", "NSTL-1.2.3-build-notes.md", "md", 1, modifiedAt = 1, parentRef = "/D")
        assertThat(InboxFilingEngine.releaseOf(notes)).isEqualTo("1.2.3")
        assertThat(InboxFilingEngine.releaseOf(notes.copy(displayName = "bundle.zip", extension = "zip", archiveSample = listOf("NSTL/NSTL-1.4.0-dev18.apk")))).isEqualTo("1.4.0-dev18")
    }
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
    @Test fun projectRoleBuildsAndSupportShareTheExistingReleaseWithObservedSpelling() {
        val home = result().decisions.single().projectHome!!.copy(hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES,
            roleFolders = mapOf("Versions" to "Releases", "Notes" to "Research/Notes"))
        val items = listOf("build.apk", "notes.txt", "cover.png", "backup.zip").map { name ->
            result().decisions.single().copy(projectHome = home, artifact = result().decisions.single().artifact.copy(displayName = name, extension = name.substringAfterLast('.')))
        }
        val dirs = setOf("${home.path}/RELEASES", "${home.path}/RELEASES/v1.2.3", "${home.path}/RELEASES/v1.2.3/research", "${home.path}/RELEASES/v1.2.3/research/NOTES")
        val edited = FilingReleaseConvention.reconcile(InboxFilingResult(items), dirs)
        assertThat(edited.proposed.map { it.destinationDirectory }).containsExactly("${home.path}/RELEASES/v1.2.3", "${home.path}/RELEASES/v1.2.3/research/NOTES", "${home.path}/RELEASES/v1.2.3/Images", "${home.path}/RELEASES/v1.2.3/Archive").inOrder()
    }
    @Test fun mixedRootAndVersionLayoutsForTheSameReleaseRequireAChoice() {
        val input = result().decisions.single().let { it.copy(projectHome = it.projectHome!!.copy(hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)) }
        val edited = FilingReleaseConvention.reconcile(InboxFilingResult(listOf(input)), setOf("/Documents/NSTL/v1.2.3", "/Documents/NSTL/Versions/1.2.3"))
        assertThat(edited.proposed).isEmpty()
    }
    @Test fun newReleasesInheritOnlyOneObservedLayoutAndPrefixAndRetainSuffixIdentity() {
        val input = result().decisions.single().let { it.copy(projectHome = it.projectHome!!.copy(hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES), release = "2.0.0-RC4") }
        val dirs = setOf("/Documents/NSTL/releases", "/Documents/NSTL/Versions/v1.9.0")
        assertThat(FilingReleaseConvention.reconcile(InboxFilingResult(listOf(input)), dirs).proposed.single().destinationDirectory)
            .isEqualTo("/Documents/NSTL/Versions/v2.0.0-RC4")
        assertThat(FilingReleaseConvention.reconcile(InboxFilingResult(listOf(input)), dirs + "/Documents/NSTL/1.8.0").proposed).isEmpty()
    }
    @Test fun roleCaseConflictsAndUnreadableLayoutsDoNotBecomeAutomaticMoves() {
        val input = result().decisions.single().let { it.copy(projectHome = it.projectHome!!.copy(hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES), artifact = it.artifact.copy(displayName = "notes.txt", extension = "txt")) }
        val dirs = setOf("/Documents/NSTL/Versions/v1.2.3", "/Documents/NSTL/Versions/v1.2.3/notes", "/Documents/NSTL/Versions/v1.2.3/Notes")
        assertThat(FilingReleaseConvention.reconcile(InboxFilingResult(listOf(input)), dirs).proposed).isEmpty()
        assertThat(FilingReleaseConvention.reconcile(InboxFilingResult(listOf(input)), emptySet(), mapOf("/Documents/NSTL" to "Access was revoked")).unresolved.single().evidenceSummary).contains("revoked")
    }
    @Test fun directoriesAndOrdinaryProjectRolesRemainIntact() {
        val home = result().decisions.single().projectHome!!.copy(hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val input = result().decisions.single().copy(projectHome = home, artifact = result().decisions.single().artifact.copy(isDirectory = true))
        assertThat(FilingReleaseConvention.reconcile(InboxFilingResult(listOf(input)), emptySet()).decisions.single()).isEqualTo(input)
        assertThat(InboxFilingEngine.destinationFor(home, null, input.artifact.copy(isDirectory = false, displayName = "notes.txt", extension = "txt"))).isEqualTo("/Documents/NSTL/Notes")
    }
}
