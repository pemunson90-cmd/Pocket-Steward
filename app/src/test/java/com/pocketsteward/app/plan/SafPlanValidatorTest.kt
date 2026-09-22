package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.executor.SingleFolderIndex
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import org.junit.Test

class SafPlanValidatorTest {
    private val root = FileRef.Saf("content://provider/tree/root/document/root")
    private val source = FileRef.Saf("content://provider/tree/root/document/root%2Fsource.txt")

    private fun index(
        vararg children: FileEntry,
    ) = SingleFolderIndex(root, children.toList())

    private fun sourceEntry() = FileEntry(
        ref = source,
        displayName = "source.txt",
        isDirectory = false,
        parentRef = root,
    )

    @Test
    fun nestedSafCreateAndMoveValidateWithSymbolicChildren() {
        val project = root.child("Projects")
        val leaf = project.child("Writing")
        val destination = leaf.child("source.txt")
        val operations = listOf(
            PlannedOperation.CreateDirectory(root, "Projects", "group"),
            PlannedOperation.CreateDirectory(project, "Writing", "group"),
            PlannedOperation.Move(source, destination, "move inside granted tree"),
        )

        val result = PlanValidator.validate(
            operations,
            index(sourceEntry()),
        )

        assertThat(result.rejected).isEmpty()
        assertThat(result.accepted).containsExactlyElementsIn(operations).inOrder()
    }

    @Test
    fun safCopyToProspectiveChildValidatesAndPreservesSourceSemantics() {
        val destination = root.child("source-copy.txt")
        val copy = PlannedOperation.Copy(source, destination, "copy")

        val result = PlanValidator.validate(
            listOf(copy),
            index(sourceEntry()),
        )

        assertThat(result.accepted).containsExactly(copy)
    }

    @Test
    fun safRenameUsesIndexedParentAndCatchesCaseInsensitiveCollision() {
        val existing = FileRef.Saf("content://provider/tree/root/document/root%2FREPORT.TXT")
        val result = PlanValidator.validate(
            listOf(
                PlannedOperation.Rename(
                    source = source,
                    newName = "report.txt",
                    reason = "rename",
                ),
            ),
            index(
                sourceEntry(),
                FileEntry(
                    ref = existing,
                    displayName = "REPORT.TXT",
                    isDirectory = false,
                    parentRef = root,
                ),
            ),
        )

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("differently-cased")
    }

    @Test
    fun safTrashValidatesOnlyKnownSource() {
        val known = PlannedOperation.Trash(source, "quarantine")
        val missing = PlannedOperation.Trash(
            FileRef.Saf("content://provider/tree/root/document/root%2Fmissing.txt"),
            "quarantine",
        )

        assertThat(PlanValidator.validate(listOf(known), index(sourceEntry())).accepted)
            .containsExactly(known)
        assertThat(PlanValidator.validate(listOf(missing), index(sourceEntry())).accepted)
            .isEmpty()
    }

    @Test
    fun safSymbolicChildCannotEscapeWithTraversal() {
        val unsafe = runCatching { root.child("../escape") }
        assertThat(unsafe.isFailure).isTrue()
    }
    @Test
    fun `rejects recursive SAF directory move`() {
        val folder = FileRef.Saf("content://provider/tree/root/document/root%2FProject")
        val nested = FileRef.Child(folder, "Nested")
        val destination = FileRef.Child(nested, "Project")
        val operations = listOf(
            PlannedOperation.CreateDirectory(
                parent = folder,
                name = "Nested",
                reason = "planned nested folder",
            ),
            PlannedOperation.Move(
                source = folder,
                destination = destination,
                reason = "recursive move",
            ),
        )
        val result = PlanValidator.validate(
            operations,
            index(
                FileEntry(
                    ref = folder,
                    displayName = "Project",
                    isDirectory = true,
                    parentRef = root,
                ),
            ),
        )

        assertThat(result.accepted).containsExactly(operations.first())
        assertThat(result.rejected).hasSize(1)
        assertThat(result.rejected.single().reason).contains("recursive move")
    }
}
