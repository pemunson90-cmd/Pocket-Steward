package com.pocketsteward.app.scan

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.ScanCheckpoint
import com.pocketsteward.app.data.db.ScanCheckpointDao
import com.pocketsteward.app.data.db.ScanStatus
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue

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
) {
    suspend fun scan(root: FileRef, onProgress: (ScanProgress) -> Unit = {}) {
        val scopeKey = root.rawValue()
        val existing = scanCheckpointDao.get(scopeKey)

        val isResume = existing != null && existing.status == ScanStatus.RUNNING
        val startedAt = if (isResume) existing!!.startedAt else System.currentTimeMillis()

        val queue = ArrayDeque<FileRef>()
        var processedCount: Int

        if (isResume) {
            queue.addAll(FileRefCodec.decodeList(existing!!.pendingDirectoriesJson))
            processedCount = existing.processedCount
        } else {
            fileRecordDao.clearScopeRoot(scopeKey)
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
            fileRecordDao.upsert(gateway.stat(root).toFileRecord(scopeKey, parent = null))
            queue.add(root)
            processedCount = 0
            persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.RUNNING, startedAt)
        }

        try {
            while (queue.isNotEmpty()) {
                val directory = queue.removeFirst()
                val children = gateway.listChildren(directory)

                val batch = children.map { entry ->
                    val meta = gateway.stat(entry.ref)
                    if (meta.isDirectory) queue.addLast(entry.ref)
                    meta.toFileRecord(scopeKey, parent = directory)
                }
                if (batch.isNotEmpty()) {
                    fileRecordDao.upsertAll(batch)
                }
                processedCount += batch.size

                persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.RUNNING, startedAt)
                onProgress(ScanProgress(processedCount, directory.rawValue(), ScanPhase.SCANNING))
            }

            persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.COMPLETED, startedAt)
            onProgress(ScanProgress(processedCount, null, ScanPhase.COMPLETED))
        } catch (t: Throwable) {
            persistCheckpoint(scopeKey, queue, processedCount, ScanStatus.FAILED, startedAt)
            onProgress(ScanProgress(processedCount, null, ScanPhase.FAILED))
            throw t
        }
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

private fun FileMetadata.toFileRecord(scopeRootRef: String, parent: FileRef?): FileRecord {
    val rawRef = ref.rawValue()
    return FileRecord(
        stableRef = rawRef,
        scopeRootRef = scopeRootRef,
        displayName = displayName,
        extension = extension,
        mimeType = mimeType,
        absolutePathOrUri = rawRef,
        parentRef = parent?.rawValue(),
        sizeBytes = sizeBytes,
        createdAt = createdAtEpochMs,
        modifiedAt = modifiedAtEpochMs,
        lastScannedAt = System.currentTimeMillis(),
        isDirectory = isDirectory,
        isHidden = isHidden,
    )
}
