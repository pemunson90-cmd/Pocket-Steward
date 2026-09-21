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
    private companion object {
        const val STATE_CHECKPOINT_INTERVAL = 25
    }

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

    suspend fun queueRoot(sourceRoot: String, eligibleCount: Int): ContentIndexJob {
        val root = sourceRoot.trimEnd('/')
        require(root.isNotBlank()) { "Content index queue needs a source root." }
        val now = System.currentTimeMillis()
        val current = dao.getJob(root)
        val resumable = current != null &&
            current.extractorVersion == ContentIndexPolicy.EXTRACTOR_VERSION &&
            current.status in setOf(
                ContentIndexJobStatus.QUEUED.name,
                ContentIndexJobStatus.RUNNING.name,
                ContentIndexJobStatus.PAUSED.name,
            )

        val job = if (resumable) {
            current!!.copy(
                eligibleCount = eligibleCount,
                updatedAt = now,
                error = null,
            )
        } else {
            ContentIndexJob(
                sourceRoot = root,
                status = ContentIndexJobStatus.QUEUED.name,
                cursorRef = null,
                eligibleCount = eligibleCount,
                processedCount = 0,
                reused = 0,
                extracted = 0,
                unsupported = 0,
                failed = 0,
                removedStale = 0,
                startedAt = now,
                updatedAt = now,
                extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
                error = null,
            )
        }
        dao.putJob(job)
        return job
    }

    suspend fun refreshRootResumable(
        candidates: List<ContentIndexCandidate>,
        sourceRoot: String,
        shouldPause: () -> Boolean = { false },
        onProgress: (ContentIndexJob) -> Unit = {},
    ): ContentIndexJob {
        val root = sourceRoot.trimEnd('/')
        require(root.isNotBlank()) { "Content index refresh needs a source root." }

        val sorted = candidates
            .filter { !it.record.isDirectory && it.sourceRoot.trimEnd('/') == root }
            .distinctBy { it.record.stableRef }
            .sortedBy { it.record.stableRef }

        val now = System.currentTimeMillis()
        val previous = dao.getJob(root)
        val previousStatus = previous?.status
        val resumable = previous != null &&
            previous.extractorVersion == ContentIndexPolicy.EXTRACTOR_VERSION &&
            previousStatus in setOf(
                ContentIndexJobStatus.QUEUED.name,
                ContentIndexJobStatus.RUNNING.name,
                ContentIndexJobStatus.PAUSED.name,
            )

        var job = if (resumable) {
            previous!!.copy(
                status = ContentIndexJobStatus.RUNNING.name,
                eligibleCount = sorted.size,
                updatedAt = now,
                error = null,
            )
        } else {
            ContentIndexJob(
                sourceRoot = root,
                status = ContentIndexJobStatus.RUNNING.name,
                cursorRef = null,
                eligibleCount = sorted.size,
                processedCount = 0,
                reused = 0,
                extracted = 0,
                unsupported = 0,
                failed = 0,
                removedStale = 0,
                startedAt = now,
                updatedAt = now,
                extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
                error = null,
            )
        }

        var startIndex = 0
        if (resumable && job.cursorRef != null) {
            val cursor = job.cursorRef!!
            startIndex = sorted.indexOfFirst { it.record.stableRef > cursor }
                .let { if (it < 0) sorted.size else it }
        }

        if (startIndex == 0) {
            val currentRefs = sorted.mapTo(hashSetOf()) { it.record.stableRef }
            val indexedRefs = dao.getStableRefsForRoot(root)
            var removed = 0
            for (stale in indexedRefs) {
                if (stale !in currentRefs) {
                    dao.removeDocument(stale)
                    removed++
                }
            }
            job = job.copy(removedStale = removed)
        }

        dao.putJob(job)
        dao.putState(
            ContentIndexState(
                sourceRoot = root,
                eligibleCount = sorted.size,
                processedCount = startIndex,
                completed = false,
                startedAt = job.startedAt,
                updatedAt = System.currentTimeMillis(),
                extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
            ),
        )
        onProgress(job)

        for (index in startIndex until sorted.size) {
            currentCoroutineContext().ensureActive()

            if (shouldPause()) {
                job = job.copy(
                    status = ContentIndexJobStatus.PAUSED.name,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.putJob(job)
                onProgress(job)
                return job
            }

            val candidate = sorted[index]
            val record = candidate.record
            val existing = dao.getDocument(record.stableRef)

            if (ContentIndexPolicy.canReuse(existing, record)) {
                job = job.copy(reused = job.reused + 1)
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
                        job = job.copy(extracted = job.extracted + 1)
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
                        job = job.copy(unsupported = job.unsupported + 1)
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
                        job = job.copy(failed = job.failed + 1)
                    }
                }
            }

            job = job.copy(
                cursorRef = record.stableRef,
                processedCount = index + 1,
                status = ContentIndexJobStatus.RUNNING.name,
                updatedAt = System.currentTimeMillis(),
                error = null,
            )
            // Cursor durability is intentionally file-granular. A service
            // timeout can therefore repeat at most the current file, never
            // lose an entire folder's indexing progress.
            dao.putJob(job)

            if ((index + 1) % STATE_CHECKPOINT_INTERVAL == 0 || index == sorted.lastIndex) {
                dao.putState(
                    ContentIndexState(
                        sourceRoot = root,
                        eligibleCount = sorted.size,
                        processedCount = index + 1,
                        completed = false,
                        startedAt = job.startedAt,
                        updatedAt = System.currentTimeMillis(),
                        extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
                    ),
                )
            }
            onProgress(job)
        }

        job = job.copy(
            status = ContentIndexJobStatus.COMPLETED.name,
            processedCount = sorted.size,
            updatedAt = System.currentTimeMillis(),
            error = null,
        )
        dao.putJob(job)
        dao.putState(
            ContentIndexState(
                sourceRoot = root,
                eligibleCount = sorted.size,
                processedCount = sorted.size,
                completed = true,
                startedAt = job.startedAt,
                updatedAt = job.updatedAt,
                extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
            ),
        )
        onProgress(job)
        return job
    }

    suspend fun markPaused(sourceRoot: String, reason: String? = null) {
        val root = sourceRoot.trimEnd('/')
        val current = dao.getJob(root) ?: return
        if (current.status == ContentIndexJobStatus.COMPLETED.name) return
        dao.putJob(
            current.copy(
                status = ContentIndexJobStatus.PAUSED.name,
                updatedAt = System.currentTimeMillis(),
                error = reason?.take(500),
            ),
        )
    }

    suspend fun jobs(sourceRoots: List<String>): List<ContentIndexJob> {
        val roots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        if (roots.isEmpty()) return emptyList()
        return dao.getJobs(roots)
    }

    suspend fun indexedDocument(stableRef: String): IndexedDocument? =
        dao.getDocument(stableRef)

    suspend fun segments(stableRef: String): List<IndexedSegment> =
        dao.getSegmentsForDocument(stableRef)

    suspend fun jobSummary(sourceRoots: List<String>): ContentIndexRefreshSummary {
        val jobs = jobs(sourceRoots)
        return ContentIndexRefreshSummary(
            totalCandidates = jobs.sumOf { it.eligibleCount },
            reused = jobs.sumOf { it.reused },
            extracted = jobs.sumOf { it.extracted },
            unsupported = jobs.sumOf { it.unsupported },
            failed = jobs.sumOf { it.failed },
            removedStale = jobs.sumOf { it.removedStale },
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

    suspend fun overview(): ContentIndexOverview = ContentIndexOverview(
        documentCount = dao.countDocuments(),
        segmentCount = dao.countSegments(),
        rootCount = dao.countRoots(),
    )

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
