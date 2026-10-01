package com.pocketsteward.app.library

import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.ScanCheckpoint
import com.pocketsteward.app.data.db.ScanCheckpointDao
import com.pocketsteward.app.data.db.ScanStatus
import com.pocketsteward.app.data.settings.StorageAccessState
import com.pocketsteward.app.scan.FileRefCodec
import com.pocketsteward.app.scan.FileScanner
import com.pocketsteward.app.scan.ScanProgress
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.ensureActive

data class LibraryStatus(
    val rootKey: String?,
    val fileCount: Int,
    /** When the last full walk finished, or null if one never has. */
    val lastCompletedAt: Long?,
    /** A walk is under way right now, or was interrupted and will resume. */
    val inProgress: Boolean,
    val processedSoFar: Int,
)

/**
 * The whole-storage inventory. Physically it is an ordinary scan scope
 * rooted at the top of what the app can reach: shared storage with full
 * access, or the granted folder otherwise. Everything else (instant folder
 * scans, the browser's categories and search) reads from it.
 *
 * Read-only toward storage: it lists and stats, exactly like a scan.
 */
class LibraryRepository(
    private val gatewayFor: (StorageAccessMode) -> StorageGateway,
    private val scannerFor: (StorageAccessMode) -> FileScanner,
    private val fileRecordDao: FileRecordDao,
    private val checkpointDao: ScanCheckpointDao,
    private val locks: ScanLocks,
    private val readLastCompleted: suspend () -> Pair<String, Long>?,
    private val writeLastCompleted: suspend (String, Long) -> Unit,
) {
    suspend fun root(access: StorageAccessState): FileRef? {
        val mode = access.mode ?: return null
        val gateway = gatewayFor(mode)
        return when (mode) {
            StorageAccessMode.DIRECT -> gateway.rootOf(StorageScope.Broad)
            StorageAccessMode.SAF -> {
                val tree = access.safTreeUri ?: return null
                gateway.rootOf(StorageScope.Tree(FileRef.Saf(tree), "Granted folder"))
            }
        }
    }

    suspend fun status(access: StorageAccessState): LibraryStatus {
        val root = runCatching { root(access) }.getOrNull()
            ?: return LibraryStatus(null, 0, null, inProgress = false, processedSoFar = 0)
        val key = root.rawValue()
        val checkpoint = checkpointDao.get(key)
        return LibraryStatus(
            rootKey = key,
            fileCount = fileRecordDao.countFilesInScope(key),
            lastCompletedAt = lastCompleted(key, checkpoint),
            inProgress = locks.isBusy(key) ||
                checkpoint?.status == ScanStatus.RUNNING || checkpoint?.status == ScanStatus.PAUSED,
            processedSoFar = checkpoint?.processedCount ?: 0,
        )
    }

    /**
     * Walks the whole library root. Resumes an interrupted walk from its
     * saved queue. Cancellation (the system stopping background work)
     * leaves a resumable checkpoint and is rethrown.
     */
    suspend fun refresh(
        access: StorageAccessState,
        forced: Boolean = false,
        onProgress: (ScanProgress) -> Unit = {},
    ): Boolean {
        val mode = access.mode ?: return false
        val root = root(access) ?: return false
        val walked = locks.withScanLock(root.rawValue()) {
            // Whoever waited on the lock re-checks: a walk that just finished
            // (the scheduled one, or the one started on app open) is enough.
            val last = lastCompleted(root.rawValue(), checkpointDao.get(root.rawValue()))
            if (!forced && LibraryPolicy.isFresh(last, System.currentTimeMillis(), LibraryPolicy.SKIP_REPEAT_WALK_MS)) {
                false
            } else {
                scannerFor(mode).scan(root, onProgress)
                true
            }
        }
        if (walked) noteCompleted(root.rawValue())
        return true
    }

    suspend fun directoryRefreshRoots(access: StorageAccessState, directories: List<String>): List<String>? {
        if (access.mode != StorageAccessMode.DIRECT) return null
        val libraryRoot = root(access) as? FileRef.Direct ?: return null
        return DirectoryRefreshPolicy.select(libraryRoot.absolutePath, directories, java.io.File(libraryRoot.absolutePath).canonicalPath)
    }

    /** Refresh only observed directory trees; periodic/manual full walks still reconcile the whole root. */
    suspend fun refreshDirectories(access: StorageAccessState, directories: List<String>, resume: Boolean = false): Boolean {
        if (access.mode != StorageAccessMode.DIRECT) return false
        val libraryRoot = root(access) as? FileRef.Direct ?: return false
        val canonicalRoot = java.io.File(libraryRoot.absolutePath).canonicalPath
        val selected = DirectoryRefreshPolicy.select(libraryRoot.absolutePath, directories, canonicalRoot) ?: return false
        return locks.withScanLock(libraryRoot.rawValue()) {
            val libraryCheckpoint = checkpointDao.get(libraryRoot.rawValue())
            // An incomplete library needs full reconciliation instead of an incomplete slice claim.
            if (libraryCheckpoint?.status?.let { it != ScanStatus.COMPLETED } == true ||
                lastCompleted(libraryRoot.rawValue(), libraryCheckpoint) == null) return@withScanLock false
            val gateway = gatewayFor(StorageAccessMode.DIRECT)
            for (path in selected) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val file = java.io.File(path)
                if (file.canonicalPath != canonicalRoot.trimEnd('/') + path.removePrefix(libraryRoot.absolutePath.trimEnd('/')) || java.nio.file.Files.isSymbolicLink(file.toPath())) return@withScanLock false
                val ref = FileRef.Direct(path)
                val metadata = try { gateway.stat(ref) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { return@withScanLock false }
                if (!metadata.isDirectory) return@withScanLock false
                locks.withScanLock(path) {
                    val refreshKey = FileScanner.refreshScopeKey(ref)
                    if (!resume) checkpointDao.clear(refreshKey)
                    scannerFor(StorageAccessMode.DIRECT).scanRefreshSlice(ref)
                    val scopes = DirectoryRefreshPolicy.overlappingScopes(path, fileRecordDao.getKnownScopeRoots() + listOf(libraryRoot.rawValue(), path))
                    fileRecordDao.replaceRefreshedSlice(refreshKey, path, scopes)
                    checkpointDao.clear(refreshKey)
                }
            }
            // Deliberately retain the full-library completion timestamp.
            true
        }
    }

    /** Records a finished walk of the library root, whoever ran it (background job or a manual whole-storage scan). */
    suspend fun noteCompleted(rootKey: String, at: Long = System.currentTimeMillis()) {
        writeLastCompleted(rootKey.trimEnd('/'), at)
    }

    suspend fun isLibraryRoot(ref: FileRef, access: StorageAccessState): Boolean =
        runCatching { root(access) }.getOrNull()?.rawValue()?.trimEnd('/') == ref.rawValue().trimEnd('/')

    /**
     * Answers a scan of [target] from the library when it is recent enough:
     * the folder's slice of the library becomes that scan's inventory and a
     * completed checkpoint is recorded, the same end state a walk leaves.
     * Returns false when a real walk is needed.
     */
    suspend fun tryAdopt(target: FileRef, access: StorageAccessState, now: Long = System.currentTimeMillis()): Boolean {
        val root = runCatching { root(access) }.getOrNull() ?: return false
        val libraryKey = root.rawValue().trimEnd('/')
        val targetKey = target.rawValue().trimEnd('/')
        if (!LibraryPolicy.canAdopt(libraryKey, targetKey)) return false
        val library = checkpointDao.get(root.rawValue())
        val completedAt = lastCompleted(root.rawValue(), library) ?: return false
        if (!LibraryPolicy.isFresh(completedAt, now, LibraryPolicy.FRESH_FOR_SCANS_MS)) return false

        if (targetKey != libraryKey) {
            // The folder must be in the library itself, or the slice would be
            // empty for the wrong reason (a folder made after the last refresh).
            if (fileRecordDao.scopeTagCount(target.rawValue(), root.rawValue()) == 0 &&
                fileRecordDao.scopeTagCount(targetKey, root.rawValue()) == 0
            ) {
                return false
            }
            // A walk of this folder that is newer than the library wins.
            val own = checkpointDao.get(target.rawValue())
            if (own?.status == ScanStatus.COMPLETED && own.updatedAt > completedAt) return false
            fileRecordDao.adoptLibrarySlice(root.rawValue(), target.rawValue(), targetKey)
            checkpointDao.upsert(
                ScanCheckpoint(
                    scopeRootRef = target.rawValue(),
                    pendingDirectoriesJson = FileRefCodec.encodeList(emptyList()),
                    processedCount = fileRecordDao.countFilesInScope(target.rawValue()),
                    status = ScanStatus.COMPLETED,
                    startedAt = library?.startedAt ?: completedAt,
                    // The library's finish time, not now: this inventory is
                    // exactly as old as the library, and a later check for "a
                    // walk newer than the library" must not count adoptions.
                    updatedAt = completedAt,
                ),
            )
        }
        return true
    }

    /**
     * A walk in progress still has the previous complete inventory under it
     * (the scanner only drops stale entries once a walk finishes), so the
     * last finish time stays valid while a new walk runs.
     */
    private suspend fun lastCompleted(rootKey: String, checkpoint: ScanCheckpoint?): Long? {
        val stored = readLastCompleted()?.takeIf { it.first == rootKey.trimEnd('/') }?.second
        val fromCheckpoint = checkpoint?.takeIf { it.status == ScanStatus.COMPLETED }?.updatedAt
        return listOfNotNull(stored, fromCheckpoint).maxOrNull()
    }
}
