package com.pocketsteward.app.share

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class ShareCopyPlannerTest {
    private val root = FileRef.Saf("content://provider/tree/root/document/root")
    private val files = listOf(file("notes.txt"), file("cover.png"))
    private val index = object : FileIndex {
        override fun exists(ref: FileRef) = ref == root || files.any { FileRef.Saf(it.stableRef) == ref }
        override fun isDirectory(ref: FileRef) = ref == root
        override fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef?) = null
    }
    @Test fun mixedShareStaysInOneProjectWithCopyOnlySourceOperations() {
        val operations = ShareCopyPlanner.build(files, root, "Lilith", mapOf("Notes" to "Research"), index)
        assertThat(operations.filterIsInstance<PlannedOperation.CreateDirectory>().map { it.name }).containsExactly("Lilith", "Research", "Images").inOrder()
        assertThat(operations.filterIsInstance<PlannedOperation.Copy>()).hasSize(2)
        assertThat(operations.none { it is PlannedOperation.Move || it is PlannedOperation.Trash }).isTrue()
        assertThat(PlanValidator.validate(operations, index).rejected).isEmpty()
    }
    @Test fun noProjectUsesReviewedTypeFolders() {
        val operations = ShareCopyPlanner.build(files, root, "", emptyMap(), index)
        assertThat(operations.filterIsInstance<PlannedOperation.CreateDirectory>().map { it.name }).containsExactly("Documents", "Images")
    }
    @Test(expected = IllegalArgumentException::class) fun repeatedDestinationRefusesOverwrite() {
        ShareCopyPlanner.build(listOf(file("cover.png"), file("COVER.PNG").copy(stableRef = "content://provider/other")), root, "Lilith", emptyMap(), index)
    }
    @Test(expected = IllegalArgumentException::class) fun traversalTitleRejected() { ShareCopyPlanner.build(files, root, "../Lilith", emptyMap(), index) }
    @Test(expected = IllegalArgumentException::class) fun unsafeSourceNameRejected() { ShareCopyPlanner.build(listOf(file("../notes.txt")), root, "Lilith", emptyMap(), index) }
    private fun file(name: String) = FilingArtifact("content://provider/$name", name, name.substringAfterLast('.'), 10, modifiedAt = 1, parentRef = null)
}
