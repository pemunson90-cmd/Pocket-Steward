package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.rawValue
import org.junit.Test

class FilingAssignmentsTest {
    @Test fun bulkCorrectionAssignsOnlyChosenFilesAndKeepsRelatedMediaInOneHome() {
        val home = ProjectHomeCandidate("Lilith", "/storage/emulated/0/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val result = InboxFilingResult(listOf(unknown("a.txt"), unknown("cover.png"), unknown("other.txt")))
        val edited = FilingAssignments.assign(result, setOf("/inbox/a.txt", "/inbox/cover.png"), home, FilingRole.AUTO)
        assertThat(edited.decisions[0].destinationDirectory).isEqualTo(home.path)
        assertThat(edited.decisions[1].destinationDirectory).isEqualTo("${home.path}/Images")
        assertThat(edited.unresolved.single().artifact.displayName).isEqualTo("other.txt")
        assertThat(edited.proposed.all { it.confidence == FilingConfidence.STRONG }).isTrue()
    }
    @Test fun selectedTreeRoleAssignmentUsesTypedNestedReferences() {
        val root = FileRef.Saf("content://provider/document/42")
        val home = ProjectHomeCandidate("NSTL", "${root.rawValue()}/NSTL", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val result = FilingAssignments.assign(InboxFilingResult(listOf(unknown("a.txt").copy(artifact = unknown("a.txt").artifact.copy(stableRef = "content://provider/document/99")))), setOf("content://provider/document/99"), home, FilingRole.NOTES)
        val plan = InboxFilingSafPlanAdapter.build(result, root, emptyMap(), "Downloads")
        assertThat(plan.operations.filterIsInstance<PlannedOperation.CreateDirectory>().map { it.name }).containsExactly("NSTL", "Notes").inOrder()
        val destination = plan.operations.filterIsInstance<PlannedOperation.Move>().single().destination as FileRef.Child
        assertThat((destination.parent as FileRef.Child).name).isEqualTo("Notes")
        assertThat(plan.presentation.groups.single().destinationPath).isEqualTo("Downloads › NSTL › Notes")
    }
    @Test(expected = IllegalArgumentException::class) fun unknownSourceCannotBeInjected() {
        FilingAssignments.assign(InboxFilingResult(listOf(unknown("a.txt"))), setOf("/other/file"), ProjectHomeCandidate("Lilith", "/Documents/Lilith"), FilingRole.AUTO)
    }
    @Test(expected = IllegalArgumentException::class) fun unsafeProjectTitleRejected() {
        FilingAssignments.assign(InboxFilingResult(listOf(unknown("a.txt"))), setOf("/inbox/a.txt"), ProjectHomeCandidate("../Lilith", "/Documents/Lilith"), FilingRole.AUTO)
    }
    @Test fun explicitReleaseKeepsMixedRolesInOneBucketAndSplitsOnlySelectedSources() {
        val home = ProjectHomeCandidate("Lilith", "/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val input = InboxFilingResult(listOf(unknown("notes.txt"), unknown("cover.png"), unknown("bundle.zip"), unknown("other.txt")))
        val edited = FilingAssignments.assign(input, input.decisions.take(3).mapTo(hashSetOf()) { it.artifact.stableRef }, home, FilingRole.AUTO, "Draft 3")
        assertThat(edited.proposed.map { it.destinationDirectory }).containsExactly("/Documents/Lilith/Versions/Draft 3/Notes", "/Documents/Lilith/Versions/Draft 3/Images", "/Documents/Lilith/Versions/Draft 3/Archive")
        assertThat(edited.unresolved.single().artifact.displayName).isEqualTo("other.txt")
    }
    @Test fun explicitReleaseRespectsCustomRoleFoldersAndPreservesExactVersionPrefix() {
        val home = ProjectHomeCandidate("Lilith", "/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, roleFolders = mapOf("Versions" to "Releases", "Notes" to "Research/Notes"))
        val edited = FilingAssignments.assign(InboxFilingResult(listOf(unknown("a.txt"))), setOf("/inbox/a.txt"), home, FilingRole.NOTES, "v1.2.3")
        assertThat(edited.proposed.single().destinationDirectory).isEqualTo("/Documents/Lilith/Releases/v1.2.3/Research/Notes")
    }
    @Test fun versionRoleDoesNotRepeatVersionsAndFoldersStayIntact() {
        val home = ProjectHomeCandidate("Lilith", "/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val folder = unknown("Assets").let { it.copy(artifact = it.artifact.copy(isDirectory = true)) }
        val edited = FilingAssignments.assign(InboxFilingResult(listOf(folder)), setOf(folder.artifact.stableRef), home, FilingRole.VERSIONS, "v2")
        assertThat(edited.proposed.single().destinationDirectory).isEqualTo("/Documents/Lilith/Versions/v2")
    }
    @Test(expected = IllegalArgumentException::class) fun releaseCannotEscapeItsProject() {
        FilingAssignments.assign(InboxFilingResult(listOf(unknown("a.txt"))), setOf("/inbox/a.txt"), ProjectHomeCandidate("Lilith", "/Documents/Lilith"), FilingRole.AUTO, "../Other")
    }

    private fun unknown(name: String) = FilingDecision(FilingArtifact("/inbox/$name", name, name.substringAfterLast('.'), 10, modifiedAt = 1, parentRef = "/inbox"), null, null, null, null, FilingConfidence.UNRESOLVED, emptyList())
}
