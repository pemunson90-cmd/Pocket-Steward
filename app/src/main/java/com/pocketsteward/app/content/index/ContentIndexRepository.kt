package com.pocketsteward.app.content.index

import com.pocketsteward.app.content.ContentExtraction
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.content.ContentKind
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.scan.classifyByExtension
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ContentIndexCandidate(
    val record: FileRecord,
    val sourceRoot: String,
)

data class ContentIndexRefreshSummary(
    val totalCandidates: Int,
    val reused: Int,
    val extracted: Int,
    val unsupported: Int,
    val failed: Int,
    val removedStale: Int,
)

class ContentIndexRepository(
    private val dao: ContentIndexDao,
    private val inspector: ContentInspector,
) {
    suspend fun refresh(
        candidates: List<ContentIndexCandidate>,
        sourceRoots: List<String>,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): ContentIndexRefreshSummary {
        val normalizedRoots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        require(normalizedRoots.isNotEmpty()) { "Content index refresh needs at least one source root." }

        val now = System.currentTimeMillis()
        val byRoot = candidates
            .filter { !it.record.isDirectory }
            .distinctBy { it.record.stableRef }
            .groupBy { it.sourceRoot.trimEnd('/') }

        var removedStale = 0
        for (root in normalizedRoots) {
            val currentRefs = byRoot[root].orEmpty().mapTo(hashSetOf()) { it.record.stableRef }
            val indexedRefs = dao.getStableRefsForRoot(root)
            for (stale in indexedRefs) {
                if (stale !in currentRefs) {
                    dao.removeDocument(stale)
                    removedStale++
                }
            }
            dao.putState(
                ContentIndexState(
                    sourceRoot = root,
                    eligibleCount = byRoot[root].orEmpty().size,
                    processedCount = 0,
                    completed = false,
                    startedAt = now,
                    updatedAt = now,
                    extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
                ),
            )
        }

        val flattened = normalizedRoots.flatMap { byRoot[it].orEmpty() }
        var processed = 0
        var reused = 0
        var extracted = 0
        var unsupported = 0
        var failed = 0
        val processedByRoot = mutableMapOf<String, Int>()

        onProgress(0, flattened.size)

        for (candidate in flattened) {
            currentCoroutineContext().ensureActive()
            val record = candidate.record
            val root = candidate.sourceRoot.trimEnd('/')
            val existing = dao.getDocument(record.stableRef)

            if (ContentIndexPolicy.canReuse(existing, record)) {
                reused++
            } else {
                when (val extraction = inspector.extract(record)) {
                    is ContentExtraction.Text -> {
                        val segments = extraction.toSegments(record.stableRef)
                        dao.replaceDocument(
                            document = record.toIndexedDocument(
                                sourceRoot = root,
                                kind = extraction.kind,
                                status = IndexedExtractionStatus.INDEXED,
                                error = null,
                                segmentCount = segments.size,
                            ),
                            segments = segments,
                        )
                        extracted++
                    }

                    is ContentExtraction.Unsupported -> {
                        dao.replaceDocument(
                            document = record.toIndexedDocument(
                                sourceRoot = root,
                                kind = null,
                                status = IndexedExtractionStatus.UNSUPPORTED,
                                error = extraction.reason,
                                segmentCount = 0,
                            ),
                            segments = emptyList(),
                        )
                        unsupported++
                    }

                    is ContentExtraction.Failed -> {
                        dao.replaceDocument(
                            document = record.toIndexedDocument(
                                sourceRoot = root,
                                kind = null,
                                status = IndexedExtractionStatus.FAILED,
                                error = extraction.reason,
                                segmentCount = 0,
                            ),
                            segments = emptyList(),
                        )
                        failed++
                    }
                }
            }

            processed++
            processedByRoot[root] = (processedByRoot[root] ?: 0) + 1
            dao.putState(
                ContentIndexState(
                    sourceRoot = root,
                    eligibleCount = byRoot[root].orEmpty().size,
                    processedCount = processedByRoot[root] ?: 0,
                    completed = false,
                    startedAt = dao.getState(root)?.startedAt ?: now,
                    updatedAt = System.currentTimeMillis(),
                    extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
                ),
            )
            onProgress(processed, flattened.size)
        }

        for (root in normalizedRoots) {
            val state = dao.getState(root)
            dao.putState(
                ContentIndexState(
                    sourceRoot = root,
                    eligibleCount = byRoot[root].orEmpty().size,
                    processedCount = byRoot[root].orEmpty().size,
                    completed = true,
                    startedAt = state?.startedAt ?: now,
                    updatedAt = System.currentTimeMillis(),
                    extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
                ),
            )
        }

        return ContentIndexRefreshSummary(
            totalCandidates = flattened.size,
            reused = reused,
            extracted = extracted,
            unsupported = unsupported,
            failed = failed,
            removedStale = removedStale,
        )
    }

    suspend fun search(
        query: String,
        sourceRoots: List<String>,
        limit: Int = 10_000,
    ): List<IndexedSearchRow> {
        val roots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        require(roots.isNotEmpty()) { "Indexed content search needs at least one source root." }
        return dao.searchRows(
            matchQuery = ContentFtsQuery.build(query),
            sourceRoots = roots,
            limit = limit.coerceIn(1, 20_000),
        )
    }

    suspend fun state(sourceRoot: String): ContentIndexState? =
        dao.getState(sourceRoot.trimEnd('/'))

    suspend fun clear() = dao.clearAll()
}

private fun ContentExtraction.Text.toSegments(stableRef: String): List<IndexedSegment> {
    if (pages.isNotEmpty()) {
        return pages.mapIndexed { index, page ->
            IndexedSegment(
                stableRef = stableRef,
                ordinal = index,
                pageNumber = page.pageNumber,
                ocr = page.ocr,
                body = page.text,
            )
        }
    }

    if (content.isBlank()) return emptyList()

    return listOf(
        IndexedSegment(
            stableRef = stableRef,
            ordinal = 0,
            pageNumber = null,
            ocr = kind == ContentKind.PDF_OCR,
            body = content,
        ),
    )
}

private fun FileRecord.toIndexedDocument(
    sourceRoot: String,
    kind: ContentKind?,
    status: IndexedExtractionStatus,
    error: String?,
    segmentCount: Int,
): IndexedDocument = IndexedDocument(
    stableRef = stableRef,
    sourceRoot = sourceRoot,
    displayName = displayName,
    parentRef = parentRef,
    extension = extension.lowercase(),
    category = classifyByExtension(extension).name,
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
    quickFingerprint = quickFingerprint,
    contentKind = kind?.name,
    extractionStatus = status.name,
    extractionError = error?.take(500),
    extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
    indexedAt = System.currentTimeMillis(),
    segmentCount = segmentCount,
)
