package com.pocketsteward.app.scan

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.FileScope
import com.pocketsteward.app.data.db.ScanCheckpoint
import com.pocketsteward.app.data.db.ScanCheckpointDao
import com.pocketsteward.app.data.db.ScanStatus
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ScanPhase { SCANNING, COMPLETED, FAILED }

data class ScanProgress(
    val processedCount: Int,
    val currentDirectoryName: String?,
    val phase: ScanPhase,
)

/**
 * Iterative (not recursive-call) breadth-first walk over a [StorageGateway],
 * writing Level 0 metadata (plan Section 7) into Room as it goes.
 *
 * Resumability (Section 18): the pending-directory queue and processed count
 * are persisted to [ScanCheckpointDao] once per directory, immediately after
 * that directory's [FileRecord] rows are committed to [FileRecordDao] in the
 * same step — so a checkpoint is never ahead of what's actually durable.
 * Worst case on a crash, the directory being processed when the process died
 * gets re-listed on resume; re-listing is idempotent (upsert keyed on the
 * unique `stableRef` index) and re-visiting one directory's immediate
 * children is cheap, so that's an acceptable cost for staying simple and
 * provably correct rather than chasing finer-grained checkpoints here.
 *
 * `stableRef` is set equal to the raw path/URI for now, not a true
 * move-resilient identity (e.g. an inode number) — that's Milestone 2/3
 * territory, once the executor needs to track a file through a mutation
 * rather than just report where it currently sits.
 */
class FileScanner(
    private val gateway: StorageGateway,
    private val fileRecordDao: FileRecordDao,
    private val scanCheckpointDao: ScanCheckpointDao,
    private val excludedDirectory: (FileRef) -> Boolean = { false },
) {
    suspend fun scan(root: FileRef, onProgress: (ScanProgress) -> Unit = {}) = scanIntoScope(root, root.rawValue(), onProgress)

    suspend fun scanRefreshSlice(root: FileRef, onProgress: (ScanProgress) -> Unit = {}) = scanIntoScope(root, refreshScopeKey(root), onProgress)

    private suspend fun scanIntoScope(root: FileRef, scopeKey: String, onProgress: (ScanProgress) -> Unit) {
        require(!excludedDirectory(root)) { "This folder is excluded from the reachable shared-storage inventory. Choose an accessible folder." }
        val existing = scanCheckpointDao.get(scopeKey)

        // Bound once as a local so the smart cast holds through both branches
        // below — the previous shape needed `!!` twice to convince the
        // compiler of something already guaranteed by this check.
        val resumable = existing?.takeIf { it.status.isResumable() }
        val existingRoot = fileRecordDao.getByStableRef(root.rawValue())
        val previousRootGeneration = existingRoot?.lastScannedAt ?: 0L
        val startedAt = resumable?.startedAt ?: maxOf(
            System.currentTimeMillis(),
            (existing?.updatedAt ?: 0L) + 1L,
            previousRootGeneration + 1L,
        )

        val queue = ArrayDeque<FileRef>()
        var processedCount: Int

        if (resumable != null) {
            queue.addAll(FileRefCodec.decodeList(resumable.pendingDirectoriesJson))
            processedCount = resumable.processedCount
        } else {
            // Keep the previous scope snapshot while the new walk is in
            // progress. Every file we actually see gets lastScannedAt >=
            // startedAt. Only after a fully completed walk do we remove scope
            // tags whose records were not seen this time. A cancelled/crashed
            // scan therefore never destroys the last known-good inventory.
            // The walk below only ever indexes *children* it discovers via
            // listChildren — the root itself was never getting a FileRecord
            // of its own. That left a real blind spot: PlanValidator checks
            // "does this operation's parent exist in the index" for
            // CreateDirectory, and a plan creating a folder directly under
            // the scan root (the common case) would always fail that check
            // and get silently rejected, root included. Indexing the root
            // as its own record (parentRef null — it has no parent within
            // this scope) closes that for every future check against it,
            // not just this one plan.
            val rootRecord = gateway.stat(root).toFileRecord(parent = null, scanGeneration = startedAt).copy(parentRef = existingRoot?.parentRef)
            fileRecordDao.upsertFromScan(rootRecord)
            fileRecordDao.insertScopeTag(FileScope(rootRecord.stableRef, scopeKey))
            queue.add(root)
            processedCount = 0
            persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.RUNNING, startedAt)
        }

        try {
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()

                // Do not dequeue the current directory until its complete
                // immediate listing and metadata batch are durable. If
                // permission/process failure happens anywhere below, the
                // checkpoint still contains this directory and resume safely
                // replays this one idempotent unit of work.
                val directory = queue.first()
                if (excludedDirectory(directory)) {
                    // Old checkpoints may still contain a now-declared private Android subtree.
                    queue.removeFirst()
                    persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.RUNNING, startedAt)
                    continue
                }
                val children = gateway.listChildren(directory)
                val discoveredDirectories = mutableListOf<FileRef>()

                var directoryCount = 0
                for (chunk in children.chunked(256)) {
                    val batch = chunk.map { entry ->
                        currentCoroutineContext().ensureActive()
                        val meta = gateway.stat(entry.ref)
                        if (meta.isDirectory && !excludedDirectory(entry.ref)) discoveredDirectories += entry.ref
                        meta.toFileRecord(parent = directory, scanGeneration = startedAt)
                    }
                    fileRecordDao.upsertAllFromScan(batch)
                    fileRecordDao.insertScopeTags(batch.map { FileScope(it.stableRef, scopeKey) })
                    directoryCount += batch.size
                    // Keep the current directory in the durable queue until all
                    // chunks land. Partial progress is visible, but resume replays
                    // this directory without inflating the durable count.
                    if (directoryCount < children.size) onProgress(ScanProgress(processedCount + directoryCount, directory.rawValue(), ScanPhase.SCANNING))
                }

                // Only now is advancing the queue safe. Child directories are
                // appended after removing the completed parent, so a resumed
                // checkpoint cannot contain partially-discovered descendants.
                queue.removeFirst()
                discoveredDirectories.forEach(queue::addLast)
                processedCount += directoryCount

                persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.RUNNING, startedAt)
                onProgress(ScanProgress(processedCount, directory.rawValue(), ScanPhase.SCANNING))
            }

            // All records seen in this scan carry a lastScannedAt at or
            // after startedAt, including records processed before a pause and
            // then resumed from the durable queue. Anything older was not
            // observed by the completed walk and can now be detached from
            // this scope without touching records shared by another scope.
            fileRecordDao.removeStaleScopeTags(scopeKey, startedAt)
            fileRecordDao.deleteOrphanedFiles()

            persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.COMPLETED, startedAt)
            onProgress(ScanProgress(processedCount, null, ScanPhase.COMPLETED))
        } catch (cancel: CancellationException) {
            // Cancellation is an intentional pause or lifecycle interruption,
            // not evidence that the filesystem scan failed. Persist in a
            // NonCancellable context so the cancellation itself cannot abort
            // writing the resume queue.
            withContext(NonCancellable) {
                persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.PAUSED, startedAt)
            }
            throw cancel
        } catch (security: SecurityException) {
            // A SAF grant or broad-storage permission can disappear while a
            // scan is running. Nothing about the inventory is corrupt; keep
            // the durable queue so the same scan can continue after access is
            // restored instead of starting from zero.
            withContext(NonCancellable) {
                persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.PAUSED, startedAt)
            }
            throw security
        } catch (t: Throwable) {
            persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.FAILED, startedAt)
            onProgress(ScanProgress(processedCount, null, ScanPhase.FAILED))
            throw t
        }
    }

    companion object {
        fun refreshScopeKey(root: FileRef): String = "library-refresh:${root.rawValue()}"
    }

    private suspend fun persistCheckpoint(
        scopeKey: String,
        queue: ArrayDeque<FileRef>,
        processedCount: Int,
        status: ScanStatus,
        startedAt: Long,
    ) {
        scanCheckpointDao.upsert(
            ScanCheckpoint(
                scopeRootRef = scopeKey,
                pendingDirectoriesJson = FileRefCodec.encodeList(queue.toList()),
                processedCount = processedCount,
                status = status,
                startedAt = startedAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}

private fun FileMetadata.toFileRecord(
    parent: FileRef?,
    scanGeneration: Long,
): FileRecord {
    val rawRef = ref.rawValue()
    return FileRecord(
        stableRef = rawRef,
        displayName = displayName,
        extension = extension,
        mimeType = mimeType,
        absolutePathOrUri = rawRef,
        parentRef = parent?.rawValue(),
        sizeBytes = sizeBytes,
        createdAt = createdAtEpochMs,
        modifiedAt = modifiedAtEpochMs,
        lastScannedAt = scanGeneration,
        isDirectory = isDirectory,
        isHidden = isHidden,
    )
}


internal fun ScanStatus.isResumable(): Boolean =
    this == ScanStatus.RUNNING || this == ScanStatus.PAUSED
