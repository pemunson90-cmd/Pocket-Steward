package com.pocketsteward.app.content.index

import com.pocketsteward.app.content.ContentExtraction
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.content.ContentKind
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.scan.classifyByExtension
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
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

data class ContentIndexInspection(val document: IndexedDocument, val reused: Boolean)

class ContentIndexRepository(
    private val dao: ContentIndexDao,
    private val inspector: ContentInspector,
    private val inspectionAllowed: suspend () -> Boolean = { true },
    private val coordination: ContentIndexCoordination = ContentIndexCoordination.Shared,
) {
    private companion object {
        const val STATE_CHECKPOINT_INTERVAL = 25
    }

    internal fun lifecycleEpoch(): Long = coordination.epoch()

    /** Enrich one observed document without pruning unrelated rows or claiming a whole root is indexed. */
    suspend fun ensureDocument(candidate: ContentIndexCandidate, budget: com.pocketsteward.app.content.ContentInspectionBudget = com.pocketsteward.app.content.ContentInspectionBudget.FULL): ContentIndexInspection {
        val epoch = coordination.epoch()
        return inspectAtEpoch(candidate, budget, epoch)
    }

    private suspend fun inspectAtEpoch(candidate: ContentIndexCandidate, budget: com.pocketsteward.app.content.ContentInspectionBudget, epoch: Long): ContentIndexInspection =
        coordination.document(candidate.record.stableRef, epoch) { ensureDocumentLocked(candidate, budget, epoch) }

    private suspend fun ensureDocumentLocked(candidate: ContentIndexCandidate, budget: com.pocketsteward.app.content.ContentInspectionBudget, epoch: Long): ContentIndexInspection {
        currentCoroutineContext().ensureActive()
        if (!inspectionAllowed()) throw CancellationException("Content inspection is off.")
        var record = candidate.record
        val root = candidate.sourceRoot.trimEnd('/')
        require(root.isNotBlank() && !record.isDirectory)
        val existing = dao.getDocument(record.stableRef)
        var freshnessFailure = observedFailure(record)
        if (freshnessFailure == null) {
            try {
                record = record.copy(quickFingerprint = inspector.evidenceFingerprint(record))
                freshnessFailure = observedFailure(record)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { freshnessFailure = "Source cache sample could not be verified: ${failure.message ?: failure.javaClass.simpleName}" }
        }
        if (freshnessFailure == null && ContentIndexPolicy.canReuse(existing, record, budget.profile)) {
            if (!inspectionAllowed()) throw CancellationException("Content inspection is off.")
            coordination.commit(epoch) { dao.putScope(IndexedDocumentScope(record.stableRef, root)) }
            return ContentIndexInspection(requireNotNull(existing), reused = true)
        }
        val extraction = if (freshnessFailure != null) ContentExtraction.Failed(freshnessFailure) else {
            val observed = inspector.extract(record, budget)
            currentCoroutineContext().ensureActive()
            var changedDuringRead = observedFailure(record)
            if (changedDuringRead == null) {
                try {
                    if (inspector.evidenceFingerprint(record) != record.quickFingerprint) {
                        changedDuringRead = "Source bytes changed during document inspection; rebuild the review."
                    }
                    if (changedDuringRead == null) changedDuringRead = observedFailure(record)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { changedDuringRead = "Source could not be verified after inspection: ${failure.message ?: failure.javaClass.simpleName}" }
            }
            if (changedDuringRead == null) observed else ContentExtraction.Failed(changedDuringRead)
        }
        val segments = if (extraction is ContentExtraction.Text) extraction.toSegments(record.stableRef) else emptyList()
        val document = when (extraction) {
            is ContentExtraction.Text -> record.toIndexedDocument(root, extraction.kind, IndexedExtractionStatus.INDEXED, null, segments.size)
            is ContentExtraction.Unsupported -> record.toIndexedDocument(root, null, IndexedExtractionStatus.UNSUPPORTED, extraction.reason, 0)
            is ContentExtraction.Failed -> record.toIndexedDocument(root, null, IndexedExtractionStatus.FAILED, extraction.reason, 0)
        }.copy(extractionProfile = budget.profile, coverageComplete = extraction is ContentExtraction.Text && !extraction.truncated)
        currentCoroutineContext().ensureActive()
        if (!inspectionAllowed()) throw CancellationException("Content inspection is off.")
        coordination.commit(epoch) { dao.replaceDocument(document, segments) }
        return ContentIndexInspection(document, reused = false)
    }

    private suspend fun observedFailure(record: FileRecord): String? = try {
        val live = inspector.observeMetadata(record.stableRef)
        currentCoroutineContext().ensureActive()
        when {
            live.isDirectory -> "Source is now a directory; rescan before reading document evidence."
            live.ref.rawValue() != record.stableRef || live.displayName != record.displayName ||
                live.sizeBytes != record.sizeBytes || live.modifiedAtEpochMs != record.modifiedAt ->
                "Source changed during document inspection; rescan and rebuild the review."
            else -> null
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (failure: Exception) { "Source could not be verified: ${failure.message ?: failure.javaClass.simpleName}" }

    suspend fun cachedDocuments(stableRefs: List<String>): Map<String, IndexedDocument> {
        val documents = linkedMapOf<String, IndexedDocument>()
        for (chunk in stableRefs.distinct().chunked(400)) {
            currentCoroutineContext().ensureActive()
            dao.getDocuments(chunk).forEach { documents[it.stableRef] = it }
        }
        return documents
    }

    suspend fun canReuse(candidate: ContentIndexCandidate, budget: com.pocketsteward.app.content.ContentInspectionBudget): Boolean {
        if (!inspectionAllowed()) return false
        val record = candidate.record
        val existing = dao.getDocument(record.stableRef) ?: return false
        if (observedFailure(record) != null) return false
        return try {
            val sampled = record.copy(quickFingerprint = inspector.evidenceFingerprint(record))
            inspectionAllowed() && observedFailure(record) == null && ContentIndexPolicy.canReuse(existing, sampled, budget.profile)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
    }

    suspend fun excerpt(stableRef: String, maxChars: Int = 8_000): String =
        dao.getExcerptSegments(stableRef, 4).joinToString(" ") { it.body.take(maxChars.coerceIn(1, 8_000)) }
            .take(maxChars.coerceIn(1, 8_000))

    suspend fun refresh(
        candidates: List<ContentIndexCandidate>,
        sourceRoots: List<String>,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): ContentIndexRefreshSummary {
        val epoch = coordination.epoch()
        val normalizedRoots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        require(normalizedRoots.isNotEmpty()) { "Content index refresh needs at least one source root." }

        val now = System.currentTimeMillis()
        val byRoot = candidates
            .filter { !it.record.isDirectory }
            .distinctBy { it.sourceRoot.trimEnd('/') to it.record.stableRef }
            .groupBy { it.sourceRoot.trimEnd('/') }

        var removedStale = 0
        for (root in normalizedRoots) {
            val currentRefs = byRoot[root].orEmpty().mapTo(hashSetOf()) { it.record.stableRef }
            val indexedRefs = dao.getStableRefsForRoot(root)
            for (stale in indexedRefs) {
                if (stale !in currentRefs) {
                    coordination.commit(epoch) { dao.removeFromRoot(stale, root) }
                    removedStale++
                }
            }
            saveState(epoch,
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
            coordination.check(epoch)
            val inspection = inspectAtEpoch(candidate, com.pocketsteward.app.content.ContentInspectionBudget.FULL, epoch)
            coordination.check(epoch)
            if (inspection.reused) reused++ else when (inspection.document.extractionStatus) {
                IndexedExtractionStatus.INDEXED.name -> extracted++
                IndexedExtractionStatus.UNSUPPORTED.name -> unsupported++
                else -> failed++
            }

            processed++
            processedByRoot[root] = (processedByRoot[root] ?: 0) + 1
            saveState(epoch,
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
            saveState(epoch,
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
        val epoch = coordination.epoch()
        return coordination.commit(epoch) {
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
            coordination.check(epoch)
            dao.putJob(job)
            job
        }
    }

    suspend fun refreshRootResumable(
        candidates: List<ContentIndexCandidate>,
        sourceRoot: String,
        shouldPause: () -> Boolean = { false },
        epoch: Long = coordination.epoch(),
        onProgress: (ContentIndexJob) -> Unit = {},
    ): ContentIndexJob {
        return coordination.root(sourceRoot, epoch) {
            refreshRootLocked(candidates, sourceRoot, shouldPause, onProgress, epoch)
        }
    }

    private suspend fun refreshRootLocked(
        candidates: List<ContentIndexCandidate>, sourceRoot: String,
        shouldPause: () -> Boolean, onProgress: (ContentIndexJob) -> Unit, epoch: Long,
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

        // Reconcile the current manifest from the beginning. Verified cache entries avoid extraction;
        // the old lexical cursor cannot prove that earlier files were neither added nor changed.
        job = job.copy(cursorRef = null, processedCount = 0, reused = 0, extracted = 0, unsupported = 0, failed = 0)

        val currentRefs = sorted.mapTo(hashSetOf()) { it.record.stableRef }
        val indexedRefs = dao.getStableRefsForRoot(root)
        var removed = 0
        for (stale in indexedRefs) {
            if (stale !in currentRefs) {
                coordination.commit(epoch) { dao.removeFromRoot(stale, root) }
                removed++
            }
        }
        job = job.copy(removedStale = removed)

        saveJob(epoch, job)
        saveState(epoch,
            ContentIndexState(
                sourceRoot = root,
                eligibleCount = sorted.size,
                processedCount = 0,
                completed = false,
                startedAt = job.startedAt,
                updatedAt = System.currentTimeMillis(),
                extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
            ),
        )
        onProgress(job)

        for (index in sorted.indices) {
            currentCoroutineContext().ensureActive()

            if (shouldPause()) {
                job = job.copy(
                    status = ContentIndexJobStatus.PAUSED.name,
                    updatedAt = System.currentTimeMillis(),
                )
                saveJob(epoch, job)
                onProgress(job)
                return job
            }

            val candidate = sorted[index]
            val record = candidate.record
            coordination.check(epoch)
            val inspection = inspectAtEpoch(candidate, com.pocketsteward.app.content.ContentInspectionBudget.FULL, epoch)
            coordination.check(epoch)
            job = when {
                inspection.reused -> job.copy(reused = job.reused + 1)
                inspection.document.extractionStatus == IndexedExtractionStatus.INDEXED.name -> job.copy(extracted = job.extracted + 1)
                inspection.document.extractionStatus == IndexedExtractionStatus.UNSUPPORTED.name -> job.copy(unsupported = job.unsupported + 1)
                else -> job.copy(failed = job.failed + 1)
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
            saveJob(epoch, job)

            if ((index + 1) % STATE_CHECKPOINT_INTERVAL == 0 || index == sorted.lastIndex) {
                saveState(epoch,
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
        saveJob(epoch, job)
        saveState(epoch,
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

    suspend fun markPaused(sourceRoot: String, reason: String? = null, epoch: Long = coordination.epoch()) {
        coordination.pause(sourceRoot, epoch) {
            val root = sourceRoot.trimEnd('/')
            val current = dao.getJob(root) ?: return@pause
            if (current.status == ContentIndexJobStatus.COMPLETED.name) return@pause
            dao.putJob(
                current.copy(
                    status = ContentIndexJobStatus.PAUSED.name,
                    updatedAt = System.currentTimeMillis(),
                    error = reason?.take(500),
                ),
            )
        }
    }

    private suspend fun saveJob(epoch: Long, job: ContentIndexJob) = coordination.commit(epoch) { dao.putJob(job) }
    private suspend fun saveState(epoch: Long, state: ContentIndexState) = coordination.commit(epoch) { dao.putState(state) }

    suspend fun jobs(sourceRoots: List<String>): List<ContentIndexJob> {
        val roots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        if (roots.isEmpty()) return emptyList()
        return dao.getJobs(roots)
    }

    suspend fun indexedDocuments(sourceRoots: List<String>): List<IndexedDocument> {
        val roots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        if (roots.isEmpty()) return emptyList()
        return dao.getIndexedDocumentsForRoots(roots)
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
        if (!inspectionAllowed()) return emptyList()
        val roots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        require(roots.isNotEmpty()) { "Indexed content search needs at least one source root." }
        val rows = dao.searchRows(
            matchQuery = ContentFtsQuery.build(query),
            sourceRoots = roots,
            limit = limit.coerceIn(1, 20_000),
        )
        val current = ContentEvidenceVerifier(observeFingerprint = { snapshot ->
            inspector.evidenceFingerprint(snapshot.stableRef, requireNotNull(snapshot.size))
        }, observe = inspector::observeMetadata).currentRefs(rows.map { row ->
            ContentEvidenceSnapshot(row.stableRef, row.displayName, row.sizeBytes, row.modifiedAt, row.quickFingerprint)
        })
        return if (inspectionAllowed()) rows.filter { it.stableRef in current } else emptyList()
    }

    suspend fun state(sourceRoot: String): ContentIndexState? =
        dao.getState(sourceRoot.trimEnd('/'))

    suspend fun overview(): ContentIndexOverview = ContentIndexOverview(
        documentCount = dao.countDocuments(),
        segmentCount = dao.countSegments(),
        rootCount = dao.countRoots(),
    )

    suspend fun clear() = coordination.clear { dao.clearAll() }
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
