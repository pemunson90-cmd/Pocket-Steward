package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.*
import org.junit.Test

class FilingWorkflowDestinationsTest {
    private val root = "/storage/emulated/0"
    private val file = FilingArtifact("$root/Download/notes.txt", "notes.txt", "txt", 10, modifiedAt = 99,
        parentRef = "$root/Download", indexedText = "Project: Lilith\nNotes")

    @Test fun newHomesUseThePreferredBaseButKnownOwnersKeepTheirCurrentHome() {
        val preferred = "$root/Documents/Writing"
        val new = InboxFilingEngine.resolve(listOf(file), emptyList(), emptyList(), emptyList(), emptyList(), root,
            newProjectRoots = mapOf(file.stableRef to preferred)).decisions.single()
        assertThat(new.projectHome?.path).isEqualTo("$preferred/Lilith")
        assertThat(new.destinationDirectory).isEqualTo("$preferred/Lilith/Notes")
        val home = ProjectHomeCandidate("Lilith", "$root/Books/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = true)
        val existing = InboxFilingEngine.resolve(listOf(file), listOf(home), emptyList(), emptyList(), emptyList(), root,
            newProjectRoots = mapOf(file.stableRef to preferred)).decisions.single()
        assertThat(existing.projectHome).isEqualTo(home)
        assertThat(existing.destinationDirectory).isEqualTo("${home.path}/Notes")
    }

    @Test fun localRecipeHomesDoNotMergeDifferentSourceRootsByTitleAlone() {
        val second = file.copy(stableRef = "$root/Inbox2/notes.txt", parentRef = "$root/Inbox2")
        val result = InboxFilingEngine.resolve(listOf(file, second), emptyList(), emptyList(), emptyList(), emptyList(), root,
            newProjectRoots = mapOf(file.stableRef to "$root/Download", second.stableRef to "$root/Inbox2"))
        assertThat(result.decisions.map { it.projectHome?.path }).containsExactly("$root/Download/Lilith", "$root/Inbox2/Lilith")
    }

    @Test fun selectedTreeHomeBaseRemainsTypedAndCheckpointStaysAtItsInbox() {
        val granted = FileRef.Saf("content://provider/tree/root/document/root")
        val documents = granted.child("Documents")
        val artifact = file.copy(stableRef = "content://provider/tree/root/document/opaqueFile", parentRef = granted.rawValue())
        val result = InboxFilingEngine.resolve(listOf(artifact), emptyList(), emptyList(), emptyList(), emptyList(), granted.rawValue(),
            newProjectRoots = mapOf(artifact.stableRef to documents.rawValue()))
        val plan = InboxFilingSafPlanAdapter.build(result, granted, emptyMap(), "Library", newHomeRoot = documents)
        assertThat(plan.operations.first()).isEqualTo(PlannedOperation.CreateDirectory(granted, "Documents", "Create or reuse the workflow's reviewed home base inside the granted tree."))
        assertThat(plan.operations.filterIsInstance<PlannedOperation.Move>().single().destination)
            .isEqualTo(documents.child("Lilith").child("Notes").child("notes.txt"))
        val uncertain = result.decisions.single().copy(projectHome = null, projectName = null, destinationDirectory = null, confidence = FilingConfidence.UNRESOLVED)
        val checkpoint = InboxFilingSafPlanAdapter.build(InboxFilingResult(listOf(uncertain)), granted, emptyMap(), "Library", newHomeRoot = documents)
        assertThat(checkpoint.operations.filterIsInstance<PlannedOperation.Move>().single().destination).isEqualTo(granted.child("Uncertain").child("notes.txt"))
        assertThat(checkpoint.operations.filterIsInstance<PlannedOperation.CreateDirectory>().map { it.name }).doesNotContain("Documents")
    }
}
