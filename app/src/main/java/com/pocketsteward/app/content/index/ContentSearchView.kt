package com.pocketsteward.app.content.index

enum class ContentSearchSort {
    RELEVANCE,
    NAME_ASC,
    NAME_DESC,
    MODIFIED_NEWEST,
    MODIFIED_OLDEST,
    LARGEST,
    SMALLEST,
    PATH,
}

enum class ContentSearchProvenance {
    ANY,
    PDF,
    OCR,
    EXTRACTED_TEXT,
}

data class ContentSearchFilters(
    val sourceRoots: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val extensions: Set<String> = emptySet(),
    val provenance: ContentSearchProvenance = ContentSearchProvenance.ANY,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val pathContains: String = "",
) {
    val activeCount: Int
        get() = listOf(
            sourceRoots.isNotEmpty(),
            categories.isNotEmpty(),
            extensions.isNotEmpty(),
            provenance != ContentSearchProvenance.ANY,
            modifiedAfter != null,
            modifiedBefore != null,
            minSizeBytes != null,
            maxSizeBytes != null,
            pathContains.isNotBlank(),
        ).count { it }
}

data class IndexedSearchSnippet(
    val pageNumber: Int?,
    val ocr: Boolean,
    val text: String,
)

data class IndexedFileSearchResult(
    val stableRef: String,
    val sourceRoot: String,
    val displayName: String,
    val parentRef: String?,
    val extension: String,
    val category: String,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val contentKind: String?,
    val snippets: List<IndexedSearchSnippet>,
    val relevanceScore: Int,
) {
    val extraSnippetCount: Int get() = (snippets.size - 1).coerceAtLeast(0)
}

object ContentSearchView {
    fun group(
        rows: List<IndexedSearchRow>,
        query: String,
    ): List<IndexedFileSearchResult> {
        val tokens = query.trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }

        return rows.groupBy { it.stableRef }.map { (_, group) ->
            val first = group.first()
            val snippets = group
                .distinctBy { Triple(it.pageNumber, it.ocr, it.snippet) }
                .map {
                    IndexedSearchSnippet(
                        pageNumber = it.pageNumber,
                        ocr = it.ocr,
                        text = it.snippet,
                    )
                }
            val lowerName = first.displayName.lowercase()
            val nameTokenHits = tokens.count { it in lowerName }
            val exactNameBonus = if (tokens.isNotEmpty() && tokens.all { it in lowerName }) 30 else 0
            val segmentBonus = group.size.coerceAtMost(20) * 2
            val earlySnippetBonus = group.firstOrNull()?.snippet
                ?.lowercase()
                ?.let { snippet -> tokens.count { token -> token in snippet } }
                ?: 0

            IndexedFileSearchResult(
                stableRef = first.stableRef,
                sourceRoot = first.sourceRoot,
                displayName = first.displayName,
                parentRef = first.parentRef,
                extension = first.extension,
                category = first.category,
                sizeBytes = first.sizeBytes,
                modifiedAt = first.modifiedAt,
                contentKind = first.contentKind,
                snippets = snippets,
                relevanceScore = exactNameBonus + nameTokenHits * 10 + segmentBonus + earlySnippetBonus,
            )
        }
    }

    fun apply(
        results: List<IndexedFileSearchResult>,
        sort: ContentSearchSort,
        filters: ContentSearchFilters,
    ): List<IndexedFileSearchResult> {
        val pathNeedle = filters.pathContains.trim().lowercase()
        val filtered = results.asSequence().filter { result ->
            if (filters.sourceRoots.isNotEmpty() && result.sourceRoot !in filters.sourceRoots) return@filter false
            if (filters.categories.isNotEmpty() && result.category !in filters.categories) return@filter false
            if (filters.extensions.isNotEmpty() && result.extension.lowercase() !in filters.extensions.map { it.lowercase() }) {
                return@filter false
            }
            if (filters.modifiedAfter != null && (result.modifiedAt == null || result.modifiedAt < filters.modifiedAfter)) {
                return@filter false
            }
            if (filters.modifiedBefore != null && (result.modifiedAt == null || result.modifiedAt > filters.modifiedBefore)) {
                return@filter false
            }
            if (filters.minSizeBytes != null && result.sizeBytes < filters.minSizeBytes) return@filter false
            if (filters.maxSizeBytes != null && result.sizeBytes > filters.maxSizeBytes) return@filter false
            if (pathNeedle.isNotBlank()) {
                val haystack = listOfNotNull(result.parentRef, result.stableRef).joinToString(" ").lowercase()
                if (pathNeedle !in haystack) return@filter false
            }
            when (filters.provenance) {
                ContentSearchProvenance.ANY -> Unit
                ContentSearchProvenance.PDF -> {
                    if (!result.extension.equals("pdf", ignoreCase = true)) return@filter false
                }
                ContentSearchProvenance.OCR -> {
                    if (result.snippets.none { it.ocr }) return@filter false
                }
                ContentSearchProvenance.EXTRACTED_TEXT -> {
                    if (result.snippets.any { it.ocr }) return@filter false
                }
            }
            true
        }.toList()

        val stablePathComparator = compareBy<IndexedFileSearchResult> {
            it.parentRef.orEmpty().lowercase()
        }.thenBy { it.displayName.lowercase() }
            .thenBy { it.stableRef }

        return when (sort) {
            ContentSearchSort.RELEVANCE -> filtered.sortedWith(
                compareByDescending<IndexedFileSearchResult> { it.relevanceScore }
                    .thenByDescending { it.snippets.size }
                    .then(stablePathComparator),
            )
            ContentSearchSort.NAME_ASC -> filtered.sortedWith(
                compareBy<IndexedFileSearchResult> { it.displayName.lowercase() }
                    .thenBy { it.stableRef },
            )
            ContentSearchSort.NAME_DESC -> filtered.sortedWith(
                compareByDescending<IndexedFileSearchResult> { it.displayName.lowercase() }
                    .thenBy { it.stableRef },
            )
            ContentSearchSort.MODIFIED_NEWEST -> filtered.sortedWith(
                compareByDescending<IndexedFileSearchResult> { it.modifiedAt ?: Long.MIN_VALUE }
                    .then(stablePathComparator),
            )
            ContentSearchSort.MODIFIED_OLDEST -> filtered.sortedWith(
                compareBy<IndexedFileSearchResult> { it.modifiedAt ?: Long.MAX_VALUE }
                    .then(stablePathComparator),
            )
            ContentSearchSort.LARGEST -> filtered.sortedWith(
                compareByDescending<IndexedFileSearchResult> { it.sizeBytes }
                    .then(stablePathComparator),
            )
            ContentSearchSort.SMALLEST -> filtered.sortedWith(
                compareBy<IndexedFileSearchResult> { it.sizeBytes }
                    .then(stablePathComparator),
            )
            ContentSearchSort.PATH -> filtered.sortedWith(stablePathComparator)
        }
    }
}
