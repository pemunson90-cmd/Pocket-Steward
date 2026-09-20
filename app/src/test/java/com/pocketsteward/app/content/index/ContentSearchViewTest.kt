package com.pocketsteward.app.content.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentSearchViewTest {
    @Test
    fun groupsMultipleSegmentsIntoOneFileResult() {
        val rows = listOf(
            row("/a/doc.pdf", "doc.pdf", page = 1, ocr = false, snippet = "pain one"),
            row("/a/doc.pdf", "doc.pdf", page = 4, ocr = true, snippet = "pain two"),
        )

        val grouped = ContentSearchView.group(rows, "pain")

        assertThat(grouped).hasSize(1)
        assertThat(grouped.single().snippets).hasSize(2)
        assertThat(grouped.single().extraSnippetCount).isEqualTo(1)
    }

    @Test
    fun relevancePrefersFilenameSignal() {
        val rows = listOf(
            row("/a/pain-notes.pdf", "pain-notes.pdf", snippet = "pain"),
            row("/a/other.pdf", "other.pdf", snippet = "pain pain pain"),
        )

        val grouped = ContentSearchView.group(rows, "pain")
        val sorted = ContentSearchView.apply(
            grouped,
            ContentSearchSort.RELEVANCE,
            ContentSearchFilters(),
        )

        assertThat(sorted.first().displayName).isEqualTo("pain-notes.pdf")
    }

    @Test
    fun filtersComposeAcrossRootExtensionOcrAndPath() {
        val rows = listOf(
            row(
                ref = "/storage/Download/NSTL/a.pdf",
                name = "a.pdf",
                sourceRoot = "/storage/Download",
                parent = "/storage/Download/NSTL",
                extension = "pdf",
                ocr = true,
            ),
            row(
                ref = "/storage/Documents/b.md",
                name = "b.md",
                sourceRoot = "/storage/Documents",
                parent = "/storage/Documents",
                extension = "md",
                ocr = false,
            ),
        )

        val grouped = ContentSearchView.group(rows, "pain")
        val filtered = ContentSearchView.apply(
            grouped,
            ContentSearchSort.PATH,
            ContentSearchFilters(
                sourceRoots = setOf("/storage/Download"),
                extensions = setOf("pdf"),
                provenance = ContentSearchProvenance.OCR,
                pathContains = "nstl",
            ),
        )

        assertThat(filtered.map { it.displayName }).containsExactly("a.pdf")
    }

    @Test
    fun modifiedNullSortsLastInBothDateDirections() {
        val base = listOf(
            result("old", modified = 10L),
            result("new", modified = 20L),
            result("unknown", modified = null),
        )

        val newest = ContentSearchView.apply(base, ContentSearchSort.MODIFIED_NEWEST, ContentSearchFilters())
        val oldest = ContentSearchView.apply(base, ContentSearchSort.MODIFIED_OLDEST, ContentSearchFilters())

        assertThat(newest.map { it.displayName }).containsExactly("new", "old", "unknown").inOrder()
        assertThat(oldest.map { it.displayName }).containsExactly("old", "new", "unknown").inOrder()
    }

    private fun row(
        ref: String,
        name: String,
        sourceRoot: String = "/a",
        parent: String? = "/a",
        extension: String = "pdf",
        category: String = "DOCUMENT",
        size: Long = 100L,
        modified: Long? = 10L,
        kind: String? = "PDF_TEXT",
        page: Int? = 1,
        ocr: Boolean = false,
        snippet: String = "pain here",
    ) = IndexedSearchRow(
        segmentId = ref.hashCode().toLong() + (page ?: 0),
        stableRef = ref,
        sourceRoot = sourceRoot,
        displayName = name,
        parentRef = parent,
        extension = extension,
        category = category,
        sizeBytes = size,
        modifiedAt = modified,
        contentKind = kind,
        pageNumber = page,
        ocr = ocr,
        snippet = snippet,
    )

    private fun result(name: String, modified: Long?) = IndexedFileSearchResult(
        stableRef = "/a/$name",
        sourceRoot = "/a",
        displayName = name,
        parentRef = "/a",
        extension = "txt",
        category = "DOCUMENT",
        sizeBytes = 1,
        modifiedAt = modified,
        contentKind = "PLAIN_TEXT",
        snippets = listOf(IndexedSearchSnippet(null, false, "pain")),
        relevanceScore = 0,
    )
}
