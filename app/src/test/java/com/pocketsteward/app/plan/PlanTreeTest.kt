package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.pocketsteward.app.plan.PlanTree.Mark
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class PlanTreeTest {
    private fun d(path: String) = FileRef.Direct(path)

    @Test
    fun movesShowTheFileLeavingInNowAndArrivingInAfter() {
        val tree = PlanTree.build(
            listOf(
                PlannedOperation.CreateDirectory(d("/storage/emulated/0/Download"), "Invoices", "group"),
                PlannedOperation.Move(
                    d("/storage/emulated/0/Download/a.pdf"),
                    d("/storage/emulated/0/Download/Invoices/a.pdf"),
                    "invoice",
                ),
                PlannedOperation.Move(
                    d("/storage/emulated/0/Download/b.pdf"),
                    d("/storage/emulated/0/Download/Invoices/b.pdf"),
                    "invoice",
                ),
            ),
        )

        assertThat(tree.before.rootLabel).isEqualTo("Phone storage/Download")
        assertThat(tree.before.rows.map { it.name to it.mark })
            .containsExactly("a.pdf" to Mark.MOVE, "b.pdf" to Mark.MOVE).inOrder()

        val after = tree.after.rows
        assertThat(after.map { it.name }).containsExactly("Invoices", "a.pdf", "b.pdf").inOrder()
        val folder = after.first()
        assertWithMessage("created folder keeps its NEW mark").that(folder.mark).isEqualTo(Mark.NEW)
        assertThat(folder.isFolder).isTrue()
        assertThat(folder.fileCount).isEqualTo(2)
        assertThat(after.drop(1).map { it.depth }).containsExactly(1, 1)
    }

    @Test
    fun trashGoesUnderTheRecoverableTrashNodeAndNeverDisappears() {
        val tree = PlanTree.build(
            listOf(PlannedOperation.Trash(d("/sd/Download/copy (1).jpg"), "duplicate")),
        )
        assertThat(tree.before.rows.single().mark).isEqualTo(Mark.TRASH)
        assertThat(tree.after.rows.map { it.name })
            .containsExactly(PlanTree.TRASH_LABEL, "copy (1).jpg").inOrder()
        assertThat(tree.after.rows.last().mark).isEqualTo(Mark.TRASH)
    }

    @Test
    fun renameKeepsTheFolderAndChangesTheName() {
        val tree = PlanTree.build(
            listOf(
                PlannedOperation.Rename(d("/sd/Docs/scan001.pdf"), "Lease 2026.pdf", "rename"),
                PlannedOperation.Rename(d("/sd/Docs/scan002.pdf"), "Tax 2025.pdf", "rename"),
            ),
        )
        assertThat(tree.after.rootLabel).isEqualTo("sd/Docs")
        assertThat(tree.after.rows.map { it.name }).containsExactly("Lease 2026.pdf", "Tax 2025.pdf").inOrder()
        assertThat(tree.after.rows.all { it.mark == Mark.RENAME }).isTrue()
    }

    @Test
    fun copyLeavesTheOriginalAndAddsTheCopy() {
        val tree = PlanTree.build(
            listOf(PlannedOperation.Copy(d("/sd/A/x.txt"), d("/sd/B/x.txt"), "copy")),
        )
        assertThat(tree.after.rows.map { it.name to it.mark }).containsExactly(
            "A" to null,
            "x.txt" to null,
            "B" to null,
            "x.txt" to Mark.COPY,
        ).inOrder()
    }

    @Test
    fun singleChildFolderChainsCollapseIntoOneRow() {
        val tree = PlanTree.build(
            listOf(
                PlannedOperation.Move(d("/sd/Download/a.pdf"), d("/sd/Documents/Tax/2025/a.pdf"), "m"),
                PlannedOperation.Move(d("/sd/Download/b.pdf"), d("/sd/Pictures/b.pdf"), "m"),
            ),
        )
        assertThat(tree.after.rows.map { it.name })
            .containsExactly("Documents/Tax/2025", "a.pdf", "Pictures", "b.pdf").inOrder()
    }

    @Test
    fun foldersSortBeforeFiles() {
        val tree = PlanTree.build(
            listOf(
                PlannedOperation.Move(d("/sd/r/z.txt"), d("/sd/r/aaa.txt"), "m"),
                PlannedOperation.Move(d("/sd/r/y.txt"), d("/sd/r/Zed/y.txt"), "m"),
            ),
        )
        assertThat(tree.after.rows.first().name).isEqualTo("Zed")
    }

    @Test
    fun longTreesAreCappedAndReportWhatWasHidden() {
        val ops = (1..50).map { PlannedOperation.Trash(d("/sd/Download/f$it.bin"), "t") }
        val tree = PlanTree.build(ops, maxRows = 10)
        assertThat(tree.before.rows).hasSize(10)
        assertThat(tree.before.hiddenRows).isEqualTo(40)
    }

    @Test
    fun safDocumentIdsBecomeReadablePaths() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
            "/document/primary%3ADownload%2FInvoices%2Fa%2Bb.pdf"
        assertThat(PlanTree.safSegments(uri)).containsExactly("Download", "Invoices", "a+b.pdf").inOrder()
        val child = FileRef.Child(FileRef.Saf(uri.substringBeforeLast("%2F")), "new.pdf")
        assertThat(PlanTree.segments(child)).containsExactly("Download", "Invoices", "new.pdf").inOrder()
    }

    @Test
    fun theSharedRootNeverSwallowsAFileName() {
        assertThat(PlanTree.commonPrefix(listOf(listOf("sd", "a.txt")))).containsExactly("sd")
        assertThat(PlanTree.commonPrefix(emptyList())).isEmpty()
    }
}
