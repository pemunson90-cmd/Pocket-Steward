package com.pocketsteward.app.cleanup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val ROOT = "/sd/Download"

private fun file(path: String, parent: String?) =
    SortCandidate(stableRef = path, parentRef = parent, displayName = path.substringAfterLast('/'), isDirectory = false)

private fun dir(path: String, parent: String?) =
    SortCandidate(stableRef = path, parentRef = parent, displayName = path.substringAfterLast('/'), isDirectory = true)

class SortScopeTest {

    @Test
    fun `by default only loose files in the root are sortable`() {
        val candidates = listOf(
            file("$ROOT/loose.pdf", ROOT),
            file("$ROOT/SomeProject/cover.jpg", "$ROOT/SomeProject"),
        )

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = false)

        assertThat(partition.sortable.map { it.stableRef }).containsExactly("$ROOT/loose.pdf")
        assertThat(partition.skippedByDepth).isEqualTo(1)
    }

    @Test
    fun `opting into subfolders makes nested files sortable`() {
        val candidates = listOf(
            file("$ROOT/loose.pdf", ROOT),
            file("$ROOT/SomeProject/cover.jpg", "$ROOT/SomeProject"),
        )

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = true)

        assertThat(partition.sortable).hasSize(2)
        assertThat(partition.skippedByDepth).isEqualTo(0)
    }

    @Test
    fun `a marker file protects its folder even when subfolders are opted in`() {
        val candidates = listOf(
            file("$ROOT/SomeProject/$DO_NOT_SORT_MARKER", "$ROOT/SomeProject"),
            file("$ROOT/SomeProject/cover.jpg", "$ROOT/SomeProject"),
            file("$ROOT/SomeProject/notes.md", "$ROOT/SomeProject"),
            file("$ROOT/loose.pdf", ROOT),
        )

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = true)

        assertThat(partition.sortable.map { it.stableRef }).containsExactly("$ROOT/loose.pdf")
        assertThat(partition.skippedByProtection).isEqualTo(2)
        assertThat(partition.protectedFolders).containsExactly("$ROOT/SomeProject")
    }

    @Test
    fun `protection is recursive into deeper folders`() {
        val candidates = listOf(
            file("$ROOT/Project/$DO_NOT_SORT_MARKER", "$ROOT/Project"),
            file("$ROOT/Project/Assets/audio.mp3", "$ROOT/Project/Assets"),
        )

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = true)

        assertThat(partition.sortable).isEmpty()
        assertThat(partition.skippedByProtection).isEqualTo(1)
    }

    @Test
    fun `a sibling folder with a similar name prefix is not protected`() {
        // "/Project2" must not be swallowed by a marker in "/Project".
        val candidates = listOf(
            file("$ROOT/Project/$DO_NOT_SORT_MARKER", "$ROOT/Project"),
            file("$ROOT/Project2/free.jpg", "$ROOT/Project2"),
        )

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = true)

        assertThat(partition.sortable.map { it.stableRef }).containsExactly("$ROOT/Project2/free.jpg")
        assertThat(partition.skippedByProtection).isEqualTo(0)
    }

    @Test
    fun `the marker file itself is never sorted`() {
        val candidates = listOf(file("$ROOT/$DO_NOT_SORT_MARKER", ROOT))

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = true)

        assertThat(partition.sortable).isEmpty()
    }

    @Test
    fun `a marker directly in the scan root protects the whole scope`() {
        val candidates = listOf(
            file("$ROOT/$DO_NOT_SORT_MARKER", ROOT),
            file("$ROOT/loose.pdf", ROOT),
            file("$ROOT/Sub/nested.pdf", "$ROOT/Sub"),
        )

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = true)

        assertThat(partition.sortable).isEmpty()
        assertThat(partition.skippedByProtection).isEqualTo(2)
    }

    @Test
    fun `directories are never themselves sortable candidates`() {
        val candidates = listOf(
            dir("$ROOT/Sub", ROOT),
            file("$ROOT/loose.pdf", ROOT),
        )

        val partition = SortScope.partition(candidates, ROOT, includeSubfolders = true)

        assertThat(partition.sortable.map { it.stableRef }).containsExactly("$ROOT/loose.pdf")
    }

    @Test
    fun `a file with no parent is not treated as protected`() {
        val candidates = listOf(file("$ROOT/orphan.pdf", null))

        assertThat(SortScope.isProtected(candidates.first(), setOf(ROOT))).isFalse()
    }

    @Test
    fun `trailing slashes on the scan root do not change what counts as loose`() {
        val candidate = file("$ROOT/loose.pdf", ROOT)

        assertThat(SortScope.isDirectlyInRoot(candidate, "$ROOT/")).isTrue()
    }
}

class CleanupScopeReportTest {

    @Test
    fun `a report that spared nothing produces no lines`() {
        val report = CleanupScopeReport(
            protectedFolderCount = 0,
            skippedByProtection = 0,
            skippedByDepth = 0,
            sortedCount = 12,
        )

        assertThat(report.previewLines()).isEmpty()
    }

    @Test
    fun `protection and depth are stated separately`() {
        val report = CleanupScopeReport(
            protectedFolderCount = 2,
            skippedByProtection = 340,
            skippedByDepth = 3100,
            sortedCount = 4829,
        )

        assertThat(report.previewLines()).containsExactly(
            "2 folders protected by $DO_NOT_SORT_MARKER, sparing 340 files",
            "3100 files left alone inside existing folders",
        ).inOrder()
    }

    @Test
    fun `single counts read as singular`() {
        val report = CleanupScopeReport(
            protectedFolderCount = 1,
            skippedByProtection = 1,
            skippedByDepth = 1,
            sortedCount = 0,
        )

        assertThat(report.previewLines()).containsExactly(
            "1 folder protected by $DO_NOT_SORT_MARKER, sparing 1 file",
            "1 file left alone inside existing folders",
        ).inOrder()
    }
    @Test
    fun protectedAncestorUsesParentGraphWhenRefsAreOpaque() {
        val root = "content://provider/tree/root/document/root"
        val protected = "content://provider/tree/root/document/id-A"
        val nested = "content://provider/tree/root/document/id-B"
        val file = "content://provider/tree/root/document/id-C"
        val candidates = listOf(
            SortCandidate(
                stableRef = protected,
                parentRef = root,
                displayName = "Project",
                isDirectory = true,
            ),
            SortCandidate(
                stableRef = "content://provider/tree/root/document/marker",
                parentRef = protected,
                displayName = DO_NOT_SORT_MARKER,
                isDirectory = false,
            ),
            SortCandidate(
                stableRef = nested,
                parentRef = protected,
                displayName = "Nested",
                isDirectory = true,
            ),
            SortCandidate(
                stableRef = file,
                parentRef = nested,
                displayName = "notes.md",
                isDirectory = false,
            ),
        )

        val partition = SortScope.partition(
            candidates = candidates,
            scopeRoot = root,
            includeSubfolders = true,
        )

        assertThat(partition.sortable.map { it.stableRef }).doesNotContain(file)
        assertThat(partition.skippedByProtection).isEqualTo(1)
    }

}
