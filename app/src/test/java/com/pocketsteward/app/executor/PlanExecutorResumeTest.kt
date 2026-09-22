package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.FileScope
import com.pocketsteward.app.data.db.MutationOperationType
import com.pocketsteward.app.data.db.MutationRecord
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskJournalProgress
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.data.db.UndoState
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlanExecutorResumeTest {
    private val sourceA = FileRef.Direct("/Download/a.txt")
    private val destinationA = FileRef.Direct("/Documents/a.txt")
    private val sourceB = FileRef.Direct("/Download/b.txt")
    private val destinationB = FileRef.Direct("/Documents/b.txt")

    @Test
    fun resumeAfterFirstMove_neverReplaysCommittedSequence() = runTest {
        val operations = listOf(
            PlannedOperation.Move(sourceA, destinationA, "first"),
            PlannedOperation.Move(sourceB, destinationB, "second"),
        )
        val task = TaskRun(
            id = 7,
            requestText = "move two files",
            startedAt = 1L,
            completedAt = null,
            status = TaskRunStatus.RUNNING,
            scanSnapshotId = null,
            planJson = DurablePlanCodec.encode("move two files", operations),
            summary = null,
            scopeRootRef = "/Download",
            storageAccessMode = StorageAccessMode.DIRECT,
            undoCompletedAt = null,
        )
        val taskDao = FakeTaskRunDao(task)
        val mutationDao = FakeMutationDao(
            mutableListOf(
                MutationRecord(
                    id = 11,
                    taskRunId = task.id,
                    sequence = 0,
                    operationType = MutationOperationType.MOVE,
                    sourceBefore = FileRefJournalCodec.encode(sourceA),
                    destinationAfter = FileRefJournalCodec.encode(destinationA),
                    sourceFingerprint = null,
                    status = MutationStatus.COMMITTED,
                    executedAt = 2L,
                    undoState = UndoState.AVAILABLE,
                    undoAttemptedAt = null,
                    error = null,
                    undoError = null,
                ),
            ),
        )
        val files = FakeFileRecordDao().apply {
            seed(fileRecord(sourceB), "/Download")
        }
        val gateway = FakeGateway(
            mutableSetOf(
                FileRef.Direct("/Download"),
                FileRef.Direct("/Documents"),
                destinationA,
                sourceB,
            ),
        )

        val summary = PlanExecutor(
            gateway = gateway,
            fileRecordDao = files,
            taskRunDao = taskDao,
            mutationRecordDao = mutationDao,
        ).resume(task.id)

        assertThat(gateway.moveCalls).containsExactly(sourceB to destinationB)
        assertThat(gateway.exists(sourceA)).isFalse()
        assertThat(gateway.exists(destinationA)).isTrue()
        assertThat(gateway.exists(sourceB)).isFalse()
        assertThat(gateway.exists(destinationB)).isTrue()

        assertThat(mutationDao.records.map { it.sequence }).containsExactly(0, 1).inOrder()
        assertThat(mutationDao.records.all { it.status == MutationStatus.COMMITTED }).isTrue()
        assertThat(taskDao.current.status).isEqualTo(TaskRunStatus.COMPLETED)
        assertThat(summary.filesMoved).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(0)

        assertThat(files.getByStableRef(sourceB.absolutePath)).isNull()
        assertThat(files.getByStableRef(destinationB.absolutePath)).isNotNull()
    }

    private fun fileRecord(ref: FileRef.Direct) = FileRecord(
        stableRef = ref.absolutePath,
        displayName = ref.absolutePath.substringAfterLast('/'),
        extension = ref.absolutePath.substringAfterLast('.', ""),
        mimeType = "text/plain",
        absolutePathOrUri = ref.absolutePath,
        parentRef = ref.absolutePath.substringBeforeLast('/'),
        sizeBytes = 4L,
        createdAt = null,
        modifiedAt = 1L,
        lastScannedAt = 1L,
        isDirectory = false,
        isHidden = false,
    )

    private class FakeGateway(
        private val existing: MutableSet<FileRef>,
    ) : StorageGateway {
        val moveCalls = mutableListOf<Pair<FileRef, FileRef>>()

        override suspend fun rootOf(scope: StorageScope): FileRef = FileRef.Direct("/")
        override suspend fun listChildren(directory: FileRef): List<FileEntry> = emptyList()

        override suspend fun stat(ref: FileRef): FileMetadata {
            val direct = ref as FileRef.Direct
            val directory = direct.absolutePath == "/Download" || direct.absolutePath == "/Documents"
            return FileMetadata(
                ref = ref,
                displayName = direct.absolutePath.substringAfterLast('/'),
                extension = if (directory) "" else "txt",
                mimeType = if (directory) null else "text/plain",
                sizeBytes = if (directory) 0L else 4L,
                createdAtEpochMs = null,
                modifiedAtEpochMs = 1L,
                isDirectory = directory,
                isHidden = false,
            )
        }

        override suspend fun exists(ref: FileRef): Boolean = ref in existing
        override suspend fun openRead(ref: FileRef): InputStream =
            ByteArrayInputStream("data".toByteArray())

        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult =
            error("not used")

        override suspend fun writeTextFile(
            parent: FileRef,
            name: String,
            content: String,
        ): MutationResult = error("not used")

        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult =
            error("not used")

        override suspend fun move(source: FileRef, destination: FileRef): MutationResult {
            moveCalls += source to destination
            if (source !in existing) return MutationResult.Failure("source missing")
            if (destination in existing) return MutationResult.Failure("destination exists")
            existing -= source
            existing += destination
            return MutationResult.Success(destination)
        }

        override suspend fun rename(source: FileRef, newName: String): MutationResult =
            error("not used")

        override suspend fun trashDestination(source: FileRef): FileRef =
            error("not used")

        override suspend fun trash(source: FileRef): MutationResult =
            error("not used")

        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult =
            error("not used")
    }

    private class FakeTaskRunDao(initial: TaskRun) : TaskRunDao {
        var current = initial

        override suspend fun insert(taskRun: TaskRun): Long {
            current = taskRun.copy(id = if (taskRun.id == 0L) 7L else taskRun.id)
            return current.id
        }

        override suspend fun update(taskRun: TaskRun) {
            current = taskRun
        }

        override suspend fun getById(id: Long): TaskRun? =
            current.takeIf { it.id == id }

        override fun observeAll(): Flow<List<TaskRun>> = flowOf(listOf(current))

        override suspend fun getRunning(): List<TaskRun> =
            listOf(current).filter { it.status == TaskRunStatus.RUNNING }

        override suspend fun markRunningPaused(
            id: Long,
            completedAt: Long,
            summary: String,
        ): Int {
            if (current.id != id || current.status != TaskRunStatus.RUNNING) return 0
            current = current.copy(
                status = TaskRunStatus.CANCELLED,
                completedAt = completedAt,
                summary = summary,
            )
            return 1
        }

        override suspend fun getRunsNeedingRecovery(): List<TaskRun> =
            listOf(current).filter {
                it.status in setOf(
                    TaskRunStatus.RUNNING,
                    TaskRunStatus.NEEDS_REVIEW,
                    TaskRunStatus.UNDOING,
                    TaskRunStatus.UNDO_PARTIAL,
                )
            }
    }

    private class FakeMutationDao(
        val records: MutableList<MutationRecord>,
    ) : MutationRecordDao {
        private var nextId = (records.maxOfOrNull { it.id } ?: 0L) + 1L

        override suspend fun insert(record: MutationRecord): Long {
            val id = if (record.id == 0L) nextId++ else record.id
            records += record.copy(id = id)
            return id
        }

        override suspend fun update(record: MutationRecord) {
            val index = records.indexOfFirst { it.id == record.id }
            if (index >= 0) records[index] = record else records += record
        }

        override suspend fun getById(id: Long): MutationRecord? =
            records.firstOrNull { it.id == id }

        override suspend fun getForTaskRun(taskRunId: Long): List<MutationRecord> =
            records.filter { it.taskRunId == taskRunId }.sortedBy { it.sequence }

        override suspend fun getForTaskRunReverse(taskRunId: Long): List<MutationRecord> =
            records.filter { it.taskRunId == taskRunId }.sortedByDescending { it.sequence }

        override fun observeTaskProgress(): Flow<List<TaskJournalProgress>> = flowOf(emptyList())

        override suspend fun getAllPending(): List<MutationRecord> =
            records.filter { it.status == MutationStatus.PENDING }

        override suspend fun getAllPendingUndo(): List<MutationRecord> =
            records.filter { it.undoState == UndoState.PENDING }

        override fun observeTrashed(): Flow<List<MutationRecord>> = flowOf(emptyList())
    }

    private class FakeFileRecordDao : FileRecordDao {
        private val records = linkedMapOf<String, FileRecord>()
        private val scopes = linkedSetOf<FileScope>()
        private var nextId = 1L

        fun seed(record: FileRecord, scopeRoot: String) {
            val stored = record.copy(id = nextId++)
            records[stored.stableRef] = stored
            scopes += FileScope(stored.stableRef, scopeRoot)
        }

        override suspend fun insert(record: FileRecord): Long {
            if (record.stableRef in records) return -1L
            val id = if (record.id == 0L) nextId++ else record.id
            records[record.stableRef] = record.copy(id = id)
            return id
        }

        override suspend fun update(record: FileRecord): Int {
            if (record.stableRef !in records) return 0
            records[record.stableRef] = record
            return 1
        }

        override suspend fun insertScopeTag(scope: FileScope) {
            scopes += scope
        }

        override suspend fun insertScopeTags(scopes: List<FileScope>) {
            this.scopes += scopes
        }

        override suspend fun getById(id: Long): FileRecord? =
            records.values.firstOrNull { it.id == id }

        override fun observeChildren(parentRef: String): Flow<List<FileRecord>> =
            flowOf(records.values.filter { it.parentRef == parentRef })

        override suspend fun countChildren(parentRef: String): Int =
            records.values.count { it.parentRef == parentRef }

        override suspend fun getFilesUnderScopeRoot(scopeRootRef: String): List<FileRecord> =
            scoped(scopeRootRef).filterNot { it.isDirectory }

        override suspend fun getLargestFiles(scopeRootRef: String, limit: Int): List<FileRecord> =
            getFilesUnderScopeRoot(scopeRootRef).sortedByDescending { it.sizeBytes }.take(limit)

        override suspend fun getFilesOlderThan(
            scopeRootRef: String,
            cutoffMillis: Long,
        ): List<FileRecord> =
            getFilesUnderScopeRoot(scopeRootRef)
                .filter { it.modifiedAt != null && it.modifiedAt < cutoffMillis }

        override suspend fun getAllUnderScopeRoot(scopeRootRef: String): List<FileRecord> =
            scoped(scopeRootRef)

        override suspend fun getByStableRef(stableRef: String): FileRecord? =
            records[stableRef]

        override suspend fun deleteByStableRef(stableRef: String) {
            records.remove(stableRef)
            scopes.removeAll { it.fileRef == stableRef }
        }

        override suspend fun getKnownScopeRoots(): List<String> =
            scopes.map { it.scopeRoot }.distinct()

        override suspend fun removeScopeTags(scopeRootRef: String) {
            scopes.removeAll { it.scopeRoot == scopeRootRef }
        }

        override suspend fun removeStaleScopeTags(
            scopeRootRef: String,
            scanStartedAt: Long,
        ) {
            val staleRefs = records.values
                .filter { it.lastScannedAt < scanStartedAt }
                .mapTo(hashSetOf()) { it.stableRef }
            scopes.removeAll { it.scopeRoot == scopeRootRef && it.fileRef in staleRefs }
        }

        override suspend fun deleteOrphanedFiles() {
            val owned = scopes.mapTo(hashSetOf()) { it.fileRef }
            records.keys.removeAll { it !in owned }
        }

        override suspend fun clearAll() {
            records.clear()
            scopes.clear()
        }

        private fun scoped(scopeRootRef: String): List<FileRecord> {
            val refs = scopes
                .filter { it.scopeRoot == scopeRootRef }
                .mapTo(hashSetOf()) { it.fileRef }
            return records.values.filter { it.stableRef in refs }
        }
    }
}
