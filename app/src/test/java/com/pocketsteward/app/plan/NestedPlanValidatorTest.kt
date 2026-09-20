package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

private class NestedFileIndex(
    private val existing: Set<FileRef>,
    private val directories: Set<FileRef>,
) : FileIndex {
    override fun exists(ref: FileRef): Boolean = ref in existing || ref in directories
    override fun isDirectory(ref: FileRef): Boolean = ref in directories
    override fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef?): FileRef? = null
}

class NestedPlanValidatorTest {
    @Test
    fun createDirectoryMayUseDirectoryCreatedEarlierInSamePlan() {
        val root = FileRef.Direct("/sd/Download")
        val source = FileRef.Direct("/sd/Download/photo.jpg")
        val parent = FileRef.Direct("/sd/Download/Organized")
        val leaf = FileRef.Direct("/sd/Download/Organized/Images")
        val operations = listOf(
            PlannedOperation.CreateDirectory(root, "Organized", "parent"),
            PlannedOperation.CreateDirectory(parent, "Images", "leaf"),
            PlannedOperation.Move(source, FileRef.Direct("${leaf.absolutePath}/photo.jpg"), "image"),
        )

        val result = PlanValidator.validate(
            operations,
            NestedFileIndex(existing = setOf(source), directories = setOf(root)),
        )

        assertThat(result.rejected).isEmpty()
        assertThat(result.accepted).containsExactlyElementsIn(operations).inOrder()
    }

    @Test
    fun directoryNameCannotSmuggleMultiplePathSegments() {
        val root = FileRef.Direct("/sd/Download")
        val op = PlannedOperation.CreateDirectory(root, "A/B", "bad")

        val result = PlanValidator.validate(
            listOf(op),
            NestedFileIndex(existing = emptySet(), directories = setOf(root)),
        )

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("path separator")
    }
}
