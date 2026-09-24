package com.pocketsteward.app.browser

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class BrowserLogicTest {
    private fun f(path: String, dir: Boolean = false, size: Long = 0, mod: Long? = null) = BrowserItem(
        ref = FileRef.Direct(path),
        name = path.substringAfterLast('/'),
        isDirectory = dir,
        sizeBytes = size,
        modifiedAt = mod,
        mimeType = null,
        isHidden = false,
        parentRef = FileRef.Direct(path.substringBeforeLast('/')),
    )

    @Test
    fun foldersFirstNaturalOrderAndHiddenFiltering() {
        val items = listOf(f("/a/file10.txt"), f("/a/file2.txt"), f("/a/Zeta", dir = true), f("/a/.nomedia"), f("/a/alpha", dir = true))
        assertThat(BrowserLogic.sort(items, BrowserSort(), showHidden = false).map { it.name })
            .containsExactly("alpha", "Zeta", "file2.txt", "file10.txt").inOrder()
        assertThat(BrowserLogic.sort(items, BrowserSort(), showHidden = true).map { it.name }).contains(".nomedia")
    }

    @Test
    fun descendingSizeKeepsFoldersOnTop() {
        val items = listOf(f("/a/small", size = 1), f("/a/big", size = 9), f("/a/D", dir = true))
        assertThat(BrowserLogic.sort(items, BrowserSort(SortKey.SIZE, descending = true), false).map { it.name })
            .containsExactly("D", "big", "small").inOrder()
    }

    @Test
    fun uniqueNamesContinueExistingCounters() {
        assertThat(BrowserLogic.uniqueName("a.pdf", setOf("b.pdf"))).isEqualTo("a.pdf")
        assertThat(BrowserLogic.uniqueName("a.pdf", setOf("A.PDF"))).isEqualTo("a (2).pdf")
        assertThat(BrowserLogic.uniqueName("a.pdf", setOf("a.pdf", "a (2).pdf"))).isEqualTo("a (3).pdf")
        assertThat(BrowserLogic.uniqueName("a (2).pdf", setOf("a (2).pdf"))).isEqualTo("a (3).pdf")
        assertThat(BrowserLogic.uniqueName("Photos", setOf("photos"))).isEqualTo("Photos (2)")
    }

    @Test
    fun validNames() {
        assertThat(BrowserLogic.validName("ok name.txt")).isNull()
        assertThat(BrowserLogic.validName("  ")).isNotNull()
        assertThat(BrowserLogic.validName("a/b")).isNotNull()
        assertThat(BrowserLogic.validName("..")).isNotNull()
    }

    @Test
    fun movePasteRenamesCollisionsAndSkipsNoOps() {
        val clip = Clipboard(ClipMode.MOVE, listOf(f("/src/a.txt"), f("/dst/b.txt"), f("/src/c.txt")))
        val r = BrowserLogic.paste(clip, FileRef.Direct("/dst"), setOf("a.txt", "b.txt"))
        val moves = r.operations.map { it as PlannedOperation.Move }
        assertThat(moves.map { it.destination }).containsExactly(
            FileRef.Direct("/dst/a (2).txt"),
            FileRef.Direct("/dst/c.txt"),
        ).inOrder()
        assertThat(r.skipped).containsExactly("b.txt is already here.")
    }

    @Test
    fun aFolderCannotMoveIntoItself() {
        val clip = Clipboard(ClipMode.MOVE, listOf(f("/src/Proj", dir = true)))
        val r = BrowserLogic.paste(clip, FileRef.Direct("/src/Proj/sub"), emptySet())
        assertThat(r.operations).isEmpty()
        assertThat(r.skipped.single()).contains("inside itself")
        // A sibling whose name merely starts the same is fine.
        val ok = BrowserLogic.paste(clip, FileRef.Direct("/src/Project2"), emptySet())
        assertThat(ok.operations).hasSize(1)
    }

    @Test
    fun copyingIntoTheSameFolderMakesANumberedCopy() {
        val clip = Clipboard(ClipMode.COPY, listOf(f("/d/x.jpg")))
        val op = BrowserLogic.paste(clip, FileRef.Direct("/d"), setOf("x.jpg")).operations.single() as PlannedOperation.Copy
        assertThat(op.destination).isEqualTo(FileRef.Direct("/d/x (2).jpg"))
    }

    @Test
    fun folderCopyExpandsIntoCreateAndCopySteps() {
        val walked = WalkedFolder(
            ref = FileRef.Direct("/s/Trip"),
            name = "Trip",
            files = listOf(FileRef.Direct("/s/Trip/1.jpg") to "1.jpg"),
            folders = listOf(
                WalkedFolder(FileRef.Direct("/s/Trip/Day2"), "Day2", listOf(FileRef.Direct("/s/Trip/Day2/2.jpg") to "2.jpg"), emptyList()),
            ),
        )
        val clip = Clipboard(ClipMode.COPY, listOf(f("/s/Trip", dir = true)))
        val ops = BrowserLogic.paste(clip, FileRef.Direct("/d"), emptySet(), mapOf("/s/Trip" to walked)).operations
        assertThat(ops.map { it::class.simpleName })
            .containsExactly("CreateDirectory", "Copy", "CreateDirectory", "Copy").inOrder()
        assertThat((ops[3] as PlannedOperation.Copy).destination).isEqualTo(FileRef.Direct("/d/Trip/Day2/2.jpg"))
        assertThat(walked.fileCount).isEqualTo(2)
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("img10", "img2", "IMG1", "img02b").sortedWith(BrowserLogic.NaturalOrder)
        assertThat(sorted).containsExactly("IMG1", "img2", "img02b", "img10").inOrder()
    }

    @Test
    fun theAppsOwnTrashIsProtected() {
        val root = "/s/0"
        assertThat(BrowserLogic.isAppManaged(f("/s/0/PocketSteward", dir = true), root)).isTrue()
        assertThat(BrowserLogic.isAppManaged(f("/s/0/PocketSteward/Trash", dir = true), root)).isTrue()
        assertThat(BrowserLogic.isAppManaged(f("/s/0/PocketSteward/Trash/Download/a.pdf"), root)).isTrue()
        assertThat(BrowserLogic.isAppManaged(f("/s/0/PocketStewardNotes", dir = true), root)).isFalse()
        assertThat(BrowserLogic.isAppManaged(f("/s/0/Download/PocketSteward", dir = true), root)).isFalse()
        assertThat(BrowserLogic.isInsideTrash("/s/0/PocketSteward/Trash/Download", root)).isTrue()
        assertThat(BrowserLogic.isInsideTrash("/s/0/PocketSteward", root)).isFalse()
    }

    @Test
    fun nonAsciiDigitsDontBreakTheSortContract() {
        // Previously "10", "a" and an Arabic-Indic three formed a cycle.
        val names = (listOf("10", "a", "\u0663", "2", "b1", "b10", "\u0663\u0661") + (1..40).map { "f$it" }).shuffled()
        val sorted = names.sortedWith(BrowserLogic.NaturalOrder)
        for (i in 0 until sorted.size - 1) {
            assertThat(BrowserLogic.NaturalOrder.compare(sorted[i], sorted[i + 1])).isAtMost(0)
        }
    }

    @Test
    fun grantedFolderTrashContentsAreProtected() {
        val root = "content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs"
        fun saf(path: String, dir: Boolean = false) = BrowserItem(
            ref = FileRef.Saf("content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3A" + path.replace("/", "%2F")),
            name = path.substringAfterLast('/'), isDirectory = dir, sizeBytes = 0, modifiedAt = null,
            mimeType = null, isHidden = false, parentRef = null,
        )
        assertThat(BrowserLogic.isAppManaged(saf("Docs/PocketSteward/Trash/abc-a.pdf"), root)).isTrue()
        assertThat(BrowserLogic.isAppManaged(saf("Docs/PocketSteward/Trash", dir = true), root)).isTrue()
        assertThat(BrowserLogic.isAppManaged(saf("Docs/Taxes/a.pdf"), root)).isFalse()
        assertThat(BrowserLogic.isInsideTrash(saf("Docs/PocketSteward/Trash", dir = true).key, root)).isTrue()
        assertThat(BrowserLogic.isInsideTrash(saf("Docs/Taxes", dir = true).key, root)).isFalse()
    }
}
