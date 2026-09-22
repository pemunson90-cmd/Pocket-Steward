package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

private class FakeFileIndex(
    existing: Set<FileRef>,
    private val directories: Set<FileRef>,
    private val parents: Map<FileRef, FileRef> = emptyMap(),
) : FileIndex {
    private val existingRefs = existing + directories

    override fun exists(ref: FileRef) = ref in existingRefs
    override fun isDirectory(ref: FileRef) = ref in directories
    override fun parentOf(ref: FileRef): FileRef? = parents[ref] ?: super.parentOf(ref)

    override fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef?): FileRef? {
        if (directory !is FileRef.Direct) return null
        val prefix = directory.absolutePath.trimEnd('/') + "/"
        return existingRefs.filterIsInstance<FileRef.Direct>().firstOrNull { ref ->
            ref != excluding &&
                ref.absolutePath.startsWith(prefix) &&
                ref.absolutePath.removePrefix(prefix).let { '/' !in it } &&
                ref.absolutePath.substringAfterLast('/').equals(name, ignoreCase = true)
        }
    }
}

private fun direct(path: String) = FileRef.Direct(path)

class PlanValidatorTest {

    @Test
    fun `accepts a move to a free destination`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/foo.apk"), direct("/sd/Download")),
            directories = setOf(direct("/sd/Download"), direct("/sd/Download/APKs")),
        )
        val op = PlannedOperation.Move(direct("/sd/Download/foo.apk"), direct("/sd/Download/APKs/foo.apk"), "APK")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `rejects a move of a source not in the index`() {
        val index = FakeFileIndex(existing = emptySet(), directories = emptySet())
        val op = PlannedOperation.Move(direct("/sd/Download/ghost.apk"), direct("/sd/Download/APKs/ghost.apk"), "APK")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected).hasSize(1)
    }

    @Test
    fun `rejects a move onto an existing destination`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/foo.apk"), direct("/sd/Download/APKs"), direct("/sd/Download/APKs/foo.apk")),
            directories = setOf(direct("/sd/Download/APKs")),
        )
        val op = PlannedOperation.Move(direct("/sd/Download/foo.apk"), direct("/sd/Download/APKs/foo.apk"), "APK")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("already exists")
    }

    @Test
    fun `rejects a self-move`() {
        val index = FakeFileIndex(existing = setOf(direct("/sd/Download/foo.apk")), directories = emptySet())
        val op = PlannedOperation.Move(direct("/sd/Download/foo.apk"), direct("/sd/Download/foo.apk"), "no-op")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
    }

    @Test
    fun `rejects moving a directory inside itself`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/Sub"), direct("/sd/Download/Sub/Nested")),
            directories = setOf(direct("/sd/Download/Sub"), direct("/sd/Download/Sub/Nested")),
        )
        val op = PlannedOperation.Move(direct("/sd/Download/Sub"), direct("/sd/Download/Sub/Nested/Sub"), "bad")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("recursive")
    }

    @Test
    fun `creating a directory that already exists as a directory is a harmless no-op`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download"), direct("/sd/Download/APKs")),
            directories = setOf(direct("/sd/Download"), direct("/sd/Download/APKs")),
        )
        val op = PlannedOperation.CreateDirectory(direct("/sd/Download"), "APKs", "already there")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
    }

    @Test
    fun `creating a directory rejected when a file already occupies that name`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download"), direct("/sd/Download/APKs")),
            directories = setOf(direct("/sd/Download")),
        )
        val op = PlannedOperation.CreateDirectory(direct("/sd/Download"), "APKs", "collides with a file")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("file already exists")
    }

    @Test
    fun `rejects path traversal in a directory name`() {
        val index = FakeFileIndex(existing = setOf(direct("/sd/Download")), directories = setOf(direct("/sd/Download")))
        val op = PlannedOperation.CreateDirectory(direct("/sd/Download"), "../../etc", "malicious")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("traversal")
    }

    @Test
    fun `second operation targeting an already-claimed destination in the same plan is rejected`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/a.apk"), direct("/sd/Download/b.apk"), direct("/sd/Download/APKs")),
            directories = setOf(direct("/sd/Download/APKs")),
        )
        val opA = PlannedOperation.Move(direct("/sd/Download/a.apk"), direct("/sd/Download/APKs/x.apk"), "1")
        val opB = PlannedOperation.Move(direct("/sd/Download/b.apk"), direct("/sd/Download/APKs/x.apk"), "2")

        val result = PlanValidator.validate(listOf(opA, opB), index)

        assertThat(result.accepted).containsExactly(opA)
        assertThat(result.rejected.single().operation).isEqualTo(opB)
    }

    @Test
    fun `case-only rename of a file is not treated as colliding with itself`() {
        val index = FakeFileIndex(existing = setOf(direct("/sd/Download/FOO.txt")), directories = emptySet())
        val op = PlannedOperation.Rename(direct("/sd/Download/FOO.txt"), "foo.txt", "normalize case")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
    }

    @Test
    fun `rename rejected when a different file already has that name, case-insensitively`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/report.txt"), direct("/sd/Download/Report.TXT")),
            directories = emptySet(),
        )
        val op = PlannedOperation.Rename(direct("/sd/Download/report.txt"), "Report.TXT", "rename")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("differently-cased")
    }

    @Test
    fun `trash requires the source to exist`() {
        val index = FakeFileIndex(existing = emptySet(), directories = emptySet())
        val op = PlannedOperation.Trash(direct("/sd/Download/ghost.apk"), "cleanup")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
    }
    @Test
    fun `rejects moving a file to a parent outside the indexed scope`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/foo.apk")),
            directories = setOf(direct("/sd/Download")),
        )
        val op = PlannedOperation.Move(
            direct("/sd/Download/foo.apk"),
            direct("/sd/Documents/foo.apk"),
            "escape scope",
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("outside")
    }

    @Test
    fun `move may target a directory created earlier in the same accepted plan`() {
        val root = direct("/sd/Download")
        val source = direct("/sd/Download/foo.apk")
        val newDir = direct("/sd/Download/APKs")
        val index = FakeFileIndex(
            existing = setOf(source),
            directories = setOf(root),
        )
        val create = PlannedOperation.CreateDirectory(root, "APKs", "destination")
        val move = PlannedOperation.Move(source, direct("${newDir.absolutePath}/foo.apk"), "APK")

        val result = PlanValidator.validate(listOf(create, move), index)

        assertThat(result.accepted).containsExactly(create, move).inOrder()
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `accepts a file copy to a free destination`() {
        val source = direct("/sd/Download/report.pdf")
        val targetDir = direct("/sd/Documents")
        val op = PlannedOperation.Copy(
            source = source,
            destination = direct("/sd/Documents/report.pdf"),
            reason = "keep original and place a copy",
        )
        val index = FakeFileIndex(
            existing = setOf(source),
            directories = setOf(direct("/sd/Download"), targetDir),
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `copy never overwrites an existing destination`() {
        val source = direct("/sd/Download/report.pdf")
        val destination = direct("/sd/Documents/report.pdf")
        val op = PlannedOperation.Copy(source, destination, "copy")
        val index = FakeFileIndex(
            existing = setOf(source, destination),
            directories = setOf(direct("/sd/Download"), direct("/sd/Documents")),
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("already exists")
    }

    @Test
    fun `directory copy is rejected`() {
        val source = direct("/sd/Download/Folder")
        val op = PlannedOperation.Copy(
            source,
            direct("/sd/Documents/Folder"),
            "copy folder",
        )
        val index = FakeFileIndex(
            existing = emptySet(),
            directories = setOf(source, direct("/sd/Download"), direct("/sd/Documents")),
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("Directory copy")
    }

    @Test
    fun `SAF rename is accepted when the trusted index knows its parent`() {
        val parent = FileRef.Saf("content://provider/tree/root/document/root")
        val source = FileRef.Saf("content://provider/tree/root/document/root%2Freport.txt")
        val index = FakeFileIndex(
            existing = setOf(source),
            directories = setOf(parent),
            parents = mapOf(source to parent),
        )
        val op = PlannedOperation.Rename(source, "renamed.txt", "rename in granted tree")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `SAF protection marker write is accepted inside an indexed folder`() {
        val folder = FileRef.Saf("content://provider/tree/root/document/root%2FProject")
        val index = FakeFileIndex(
            existing = emptySet(),
            directories = setOf(folder),
        )
        val op = PlannedOperation.WriteTextFile(
            parent = folder,
            name = "POCKETSTEWARD-DO-NOT-SORT.md",
            content = "protect",
            reason = "protect folder",
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `SAF protection marker can be moved to recoverable Trash`() {
        val marker = FileRef.Saf(
            "content://provider/tree/root/document/root%2FProject%2FPOCKETSTEWARD-DO-NOT-SORT.md",
        )
        val index = FakeFileIndex(existing = setOf(marker), directories = emptySet())
        val op = PlannedOperation.Trash(marker, "unprotect")

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `SAF move may target a child of a planned directory`() {
        val root = FileRef.Saf("content://provider/tree/root/document/root")
        val source = FileRef.Saf("content://provider/tree/root/document/root%2Freport.txt")
        val plannedFolder = FileRef.Child(root, "Archive")
        val destination = FileRef.Child(plannedFolder, "report.txt")
        val index = FakeFileIndex(
            existing = setOf(source),
            directories = setOf(root),
            parents = mapOf(source to root),
        )
        val create = PlannedOperation.CreateDirectory(root, "Archive", "destination")
        val move = PlannedOperation.Move(source, destination, "archive")

        val result = PlanValidator.validate(listOf(create, move), index)

        assertThat(result.accepted).containsExactly(create, move).inOrder()
        assertThat(result.rejected).isEmpty()
    }

    @Test
    fun `SAF copy refuses an already indexed child destination`() {
        val root = FileRef.Saf("content://provider/tree/root/document/root")
        val source = FileRef.Saf("content://provider/tree/root/document/root%2Fsource.txt")
        val destination = FileRef.Child(root, "copy.txt")
        val index = FakeFileIndex(
            existing = setOf(source, destination),
            directories = setOf(root),
            parents = mapOf(source to root),
        )
        val copy = PlannedOperation.Copy(source, destination, "copy")

        val result = PlanValidator.validate(listOf(copy), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("already exists")
    }

}

class WriteTextFileValidationTest {

    @Test
    fun `a marker file in an indexed folder is accepted`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/Project/cover.jpg")),
            directories = setOf(direct("/sd/Download"), direct("/sd/Download/Project")),
        )
        val op = PlannedOperation.WriteTextFile(
            parent = FileRef.Direct("/sd/Download/Project"),
            name = "POCKETSTEWARD-DO-NOT-SORT.md",
            content = "anything",
            reason = "protect",
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).containsExactly(op)
    }

    @Test
    fun `an existing file at the destination is never overwritten`() {
        val index = FakeFileIndex(
            existing = setOf(direct("/sd/Download/Project/POCKETSTEWARD-DO-NOT-SORT.md")),
            directories = setOf(direct("/sd/Download"), direct("/sd/Download/Project")),
        )
        val op = PlannedOperation.WriteTextFile(
            parent = FileRef.Direct("/sd/Download/Project"),
            name = "POCKETSTEWARD-DO-NOT-SORT.md",
            content = "anything",
            reason = "protect",
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("already exists")
    }

    @Test
    fun `a path separator in the name is rejected`() {
        val index = FakeFileIndex(existing = emptySet(), directories = setOf(direct("/sd/Download")))
        val op = PlannedOperation.WriteTextFile(
            parent = FileRef.Direct("/sd/Download"),
            name = "sub/marker.md",
            content = "x",
            reason = "protect",
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.rejected.single().reason).contains("path separator")
    }

    @Test
    fun `traversal in the name is rejected`() {
        val index = FakeFileIndex(existing = emptySet(), directories = setOf(direct("/sd/Download")))
        val op = PlannedOperation.WriteTextFile(
            parent = FileRef.Direct("/sd/Download"),
            name = "../escape.md",
            content = "x",
            reason = "protect",
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.rejected.single().reason).contains("..")
    }

    @Test
    fun `two writes to the same destination in one plan collide`() {
        val index = FakeFileIndex(existing = emptySet(), directories = setOf(direct("/sd/Download")))
        val op = PlannedOperation.WriteTextFile(
            parent = FileRef.Direct("/sd/Download"),
            name = "manifest.md",
            content = "x",
            reason = "first",
        )

        val result = PlanValidator.validate(listOf(op, op.copy(reason = "second")), index)

        assertThat(result.accepted).hasSize(1)
        assertThat(result.rejected.single().reason).contains("already claimed")
    }
}
