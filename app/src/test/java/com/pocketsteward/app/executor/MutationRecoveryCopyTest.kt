package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
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
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MutationRecoveryCopyTest {
    private val source = FileRef.Direct("/Download/source.txt")
    private val destination = FileRef.Direct("/Documents/source.txt")

    @Test
    fun pendingCopyWithSourceAndDestination_recoversCommitted() = runTest {
        val task = task(TaskRunStatus.RUNNING)
        val record = pendingCopy()
        val taskDao = FakeTaskRunDao(task)
        val mutationDao = FakeMutationDao(record)
        val gateway = FakeGateway(setOf(source, destination))

        MutationRecovery(mutationDao, taskDao) { gateway }.recoverAll()

        val recovered = mutationDao.records.single()
        assertThat(recovered.status).isEqualTo(MutationStatus.COMMITTED)
        assertThat(recovered.undoState).isEqualTo(UndoState.AVAILABLE)
        assertThat(taskDao.current.status).isEqualTo(TaskRunStatus.COMPLETED)
    }

    @Test
    fun pendingCopyWithOnlySource_recoversFailed() = runTest {
        val taskDao = FakeTaskRunDao(task(TaskRunStatus.RUNNING))
        val mutationDao = FakeMutationDao(pendingCopy())
        val gateway = FakeGateway(setOf(source))

        MutationRecovery(mutationDao, taskDao) { gateway }.recoverAll()

        assertThat(mutationDao.records.single().status).isEqualTo(MutationStatus.FAILED)
        assertThat(taskDao.current.status).isEqualTo(TaskRunStatus.FAILED)
    }

    @Test
    fun pendingCopyWithMissingSourceAndExistingDestination_requiresReview() = runTest {
        val taskDao = FakeTaskRunDao(task(TaskRunStatus.RUNNING))
        val mutationDao = FakeMutationDao(pendingCopy())
        val gateway = FakeGateway(setOf(destination))

        MutationRecovery(mutationDao, taskDao) { gateway }.recoverAll()

        val recovered = mutationDao.records.single()
        assertThat(recovered.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
        assertThat(recovered.undoState).isEqualTo(UndoState.BLOCKED)
        assertThat(taskDao.current.status).isEqualTo(TaskRunStatus.NEEDS_REVIEW)
    }

    @Test
    fun pendingCopyWithMismatchedDestination_requiresReview() = runTest {
        val taskDao = FakeTaskRunDao(task(TaskRunStatus.RUNNING))
        val mutationDao = FakeMutationDao(
            pendingCopy().copy(sourceFingerprint = fingerprint(byteArrayOf(9))),
        )
        val gateway = FakeGateway(setOf(source, destination))

        MutationRecovery(mutationDao, taskDao) { gateway }.recoverAll()

        val recovered = mutationDao.records.single()
        assertThat(recovered.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
        assertThat(recovered.undoState).isEqualTo(UndoState.BLOCKED)
        assertThat(taskDao.current.status).isEqualTo(TaskRunStatus.NEEDS_REVIEW)
    }

    @Test
    fun interruptedCopyUndoWithDestinationGone_recoversUndone() = runTest {
        val taskDao = FakeTaskRunDao(task(TaskRunStatus.UNDOING))
        val mutationDao = FakeMutationDao(
            pendingCopy().copy(
                status = MutationStatus.COMMITTED,
                undoState = UndoState.PENDING,
            ),
        )
        val gateway = FakeGateway(setOf(source))

        MutationRecovery(mutationDao, taskDao) { gateway }.recoverAll()

        val recovered = mutationDao.records.single()
        assertThat(recovered.status).isEqualTo(MutationStatus.UNDONE)
        assertThat(recovered.undoState).isEqualTo(UndoState.UNDONE)
        assertThat(taskDao.current.status).isEqualTo(TaskRunStatus.UNDONE)
    }

    private fun task(status: TaskRunStatus): TaskRun {
        val operation = PlannedOperation.Copy(source, destination, "copy")
        return TaskRun(
            id = 7,
            requestText = "copy source",
            startedAt = 1,
            completedAt = null,
            status = status,
            scanSnapshotId = null,
            planJson = DurablePlanCodec.encode("copy source", listOf(operation)),
            summary = null,
            scopeRootRef = "/Download",
            storageAccessMode = StorageAccessMode.DIRECT,
            undoCompletedAt = null,
        )
    }

    private fun pendingCopy(): MutationRecord = MutationRecord(
        id = 9,
        taskRunId = 7,
        sequence = 0,
        operationType = MutationOperationType.COPY,
        sourceBefore = FileRefJournalCodec.encode(source),
        destinationAfter = FileRefJournalCodec.encode(destination),
        sourceFingerprint = fingerprint(byteArrayOf(1)),
        status = MutationStatus.PENDING,
        executedAt = null,
        undoState = UndoState.NOT_AVAILABLE,
        undoAttemptedAt = null,
        error = null,
        undoError = null,
    )

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class FakeTaskRunDao(initial: TaskRun) : TaskRunDao {
        var current = initial

        override suspend fun insert(taskRun: TaskRun): Long {
            current = taskRun.copy(id = if (taskRun.id == 0L) 7 else taskRun.id)
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

    private class FakeMutationDao(initial: MutationRecord) : MutationRecordDao {
        val records = mutableListOf(initial)

        override suspend fun insert(record: MutationRecord): Long {
            val id = if (record.id == 0L) (records.maxOfOrNull { it.id } ?: 0L) + 1L else record.id
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

    private class FakeGateway(
        private val existing: Set<FileRef>,
    ) : StorageGateway {
        override suspend fun rootOf(scope: StorageScope): FileRef = FileRef.Direct("/")
        override suspend fun listChildren(directory: FileRef): List<FileEntry> = emptyList()
        override suspend fun stat(ref: FileRef): FileMetadata = FileMetadata(
            ref = ref,
            displayName = ref.toString(),
            extension = "txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            createdAtEpochMs = null,
            modifiedAtEpochMs = null,
            isDirectory = false,
            isHidden = false,
        )
        override suspend fun exists(ref: FileRef): Boolean = ref in existing
        override suspend fun openRead(ref: FileRef): InputStream = ByteArrayInputStream(byteArrayOf(1))
        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult = error("not used")
        override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult = error("not used")
        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult = error("not used")
        override suspend fun move(source: FileRef, destination: FileRef): MutationResult = error("not used")
        override suspend fun rename(source: FileRef, newName: String): MutationResult = error("not used")
        override suspend fun trashDestination(source: FileRef): FileRef = error("not used")
        override suspend fun trash(source: FileRef): MutationResult = error("not used")
        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult = error("not used")
    }
}
