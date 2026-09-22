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

class MutationRecoveryCoreTest {
    private val source = FileRef.Direct("/Download/source.txt")
    private val destination = FileRef.Direct("/Documents/source.txt")
    private val parent = FileRef.Direct("/Download")
    private val written = FileRef.Direct("/Download/generated.txt")
    private val createdDirectory = FileRef.Direct("/Download/Generated")

    @Test
    fun interruptedMove_landed_recoversCommitted() = runTest {
        val operation = PlannedOperation.Move(source, destination, "move")
        val record = pending(operation, MutationOperationType.MOVE, source, destination)
        val harness = harness(operation, record, setOf(destination))

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.COMMITTED)
        assertThat(harness.record.undoState).isEqualTo(UndoState.AVAILABLE)
        assertThat(harness.task.status).isEqualTo(TaskRunStatus.COMPLETED)
    }

    @Test
    fun interruptedMove_notStarted_recoversFailed() = runTest {
        val operation = PlannedOperation.Move(source, destination, "move")
        val record = pending(operation, MutationOperationType.MOVE, source, destination)
        val harness = harness(operation, record, setOf(source))

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.FAILED)
        assertThat(harness.task.status).isEqualTo(TaskRunStatus.FAILED)
    }

    @Test
    fun interruptedMove_withBothCopies_requiresReview() = runTest {
        val operation = PlannedOperation.Move(source, destination, "move")
        val record = pending(operation, MutationOperationType.MOVE, source, destination)
        val harness = harness(operation, record, setOf(source, destination))

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
        assertThat(harness.record.undoState).isEqualTo(UndoState.BLOCKED)
        assertThat(harness.task.status).isEqualTo(TaskRunStatus.NEEDS_REVIEW)
    }

    @Test
    fun interruptedTrash_landed_recoversCommitted() = runTest {
        val operation = PlannedOperation.Trash(source, "trash")
        val record = pending(operation, MutationOperationType.TRASH, source, destination)
        val harness = harness(operation, record, setOf(destination))

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.COMMITTED)
        assertThat(harness.record.undoState).isEqualTo(UndoState.AVAILABLE)
    }

    @Test
    fun interruptedDuplicateTrash_matchingBytes_recoversCommitted() = runTest {
        val approvedBytes = "duplicate".toByteArray()
        val approvedHash = fingerprint(approvedBytes)
        val operation = PlannedOperation.Trash(source, "duplicate", sourceFingerprint = approvedHash)
        val record = pending(operation, MutationOperationType.TRASH, source, destination)
            .copy(sourceFingerprint = approvedHash)
        val harness = harness(
            operation = operation,
            record = record,
            existing = setOf(destination),
            bytesByRef = mapOf(destination to approvedBytes),
        )

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.COMMITTED)
        assertThat(harness.record.undoState).isEqualTo(UndoState.AVAILABLE)
    }

    @Test
    fun interruptedDuplicateTrash_wrongBytes_requiresReview() = runTest {
        val approvedHash = fingerprint("duplicate".toByteArray())
        val operation = PlannedOperation.Trash(source, "duplicate", sourceFingerprint = approvedHash)
        val record = pending(operation, MutationOperationType.TRASH, source, destination)
            .copy(sourceFingerprint = approvedHash)
        val harness = harness(
            operation = operation,
            record = record,
            existing = setOf(destination),
            bytesByRef = mapOf(destination to "changed".toByteArray()),
        )

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
        assertThat(harness.record.undoState).isEqualTo(UndoState.BLOCKED)
        assertThat(harness.task.status).isEqualTo(TaskRunStatus.NEEDS_REVIEW)
    }

    @Test
    fun interruptedWrite_destinationExists_recoversCommitted() = runTest {
        val operation = PlannedOperation.WriteTextFile(parent, "generated.txt", "hello", "write")
        val record = pending(operation, MutationOperationType.WRITE_TEXT_FILE, parent, written)
            .copy(sourceFingerprint = fingerprint("hello".toByteArray()))
        val harness = harness(
            operation,
            record,
            setOf(parent, written),
            bytesByRef = mapOf(written to "hello".toByteArray()),
        )

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.COMMITTED)
        assertThat(harness.record.undoState).isEqualTo(UndoState.AVAILABLE)
    }

    @Test
    fun interruptedWrite_wrongBytes_requiresReview() = runTest {
        val operation = PlannedOperation.WriteTextFile(parent, "generated.txt", "hello", "write")
        val record = pending(operation, MutationOperationType.WRITE_TEXT_FILE, parent, written)
            .copy(sourceFingerprint = fingerprint("hello".toByteArray()))
        val harness = harness(
            operation,
            record,
            setOf(parent, written),
            bytesByRef = mapOf(written to "partial".toByteArray()),
        )

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
        assertThat(harness.record.undoState).isEqualTo(UndoState.BLOCKED)
    }

    @Test
    fun interruptedWrite_destinationMissing_recoversFailed() = runTest {
        val operation = PlannedOperation.WriteTextFile(parent, "generated.txt", "hello", "write")
        val record = pending(operation, MutationOperationType.WRITE_TEXT_FILE, parent, written)
        val harness = harness(operation, record, setOf(parent))

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.FAILED)
    }

    @Test
    fun interruptedCreateDirectory_destinationExists_recoversCommitted() = runTest {
        val operation = PlannedOperation.CreateDirectory(parent, "Generated", "create")
        val record = pending(operation, MutationOperationType.CREATE_DIRECTORY, parent, createdDirectory)
        val harness = harness(operation, record, setOf(parent, createdDirectory))

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.COMMITTED)
        assertThat(harness.record.undoState).isEqualTo(UndoState.AVAILABLE)
    }

    @Test
    fun interruptedUndoMove_thatAlreadyRestored_recoversUndone() = runTest {
        val operation = PlannedOperation.Move(source, destination, "move")
        val record = pending(operation, MutationOperationType.MOVE, source, destination).copy(
            status = MutationStatus.COMMITTED,
            undoState = UndoState.PENDING,
        )
        val harness = harness(
            operation = operation,
            record = record,
            existing = setOf(source),
            status = TaskRunStatus.UNDOING,
        )

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.UNDONE)
        assertThat(harness.record.undoState).isEqualTo(UndoState.UNDONE)
        assertThat(harness.task.status).isEqualTo(TaskRunStatus.UNDONE)
    }

    @Test
    fun interruptedUndoMove_beforeInverse_isRetryable() = runTest {
        val operation = PlannedOperation.Move(source, destination, "move")
        val record = pending(operation, MutationOperationType.MOVE, source, destination).copy(
            status = MutationStatus.COMMITTED,
            undoState = UndoState.PENDING,
        )
        val harness = harness(
            operation = operation,
            record = record,
            existing = setOf(destination),
            status = TaskRunStatus.UNDOING,
        )

        harness.recover()

        assertThat(harness.record.status).isEqualTo(MutationStatus.COMMITTED)
        assertThat(harness.record.undoState).isEqualTo(UndoState.AVAILABLE)
    }

    @Test
    fun interruptedUndoMove_withBothLocationsOccupied_blocks() = runTest {
        val operation = PlannedOperation.Move(source, destination, "move")
        val record = pending(operation, MutationOperationType.MOVE, source, destination).copy(
            status = MutationStatus.COMMITTED,
            undoState = UndoState.PENDING,
        )
        val harness = harness(
            operation = operation,
            record = record,
            existing = setOf(source, destination),
            status = TaskRunStatus.UNDOING,
        )

        harness.recover()

        assertThat(harness.record.undoState).isEqualTo(UndoState.BLOCKED)
        assertThat(harness.task.status).isEqualTo(TaskRunStatus.UNDO_PARTIAL)
    }

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun pending(
        operation: PlannedOperation,
        type: MutationOperationType,
        sourceBefore: FileRef,
        destinationAfter: FileRef,
    ): MutationRecord = MutationRecord(
        id = 9,
        taskRunId = 7,
        sequence = 0,
        operationType = type,
        sourceBefore = FileRefJournalCodec.encode(sourceBefore),
        destinationAfter = FileRefJournalCodec.encode(destinationAfter),
        sourceFingerprint = null,
        status = MutationStatus.PENDING,
        executedAt = null,
        undoState = UndoState.NOT_AVAILABLE,
        undoAttemptedAt = null,
        error = null,
        undoError = null,
    )

    private fun harness(
        operation: PlannedOperation,
        record: MutationRecord,
        existing: Set<FileRef>,
        status: TaskRunStatus = TaskRunStatus.RUNNING,
        bytesByRef: Map<FileRef, ByteArray> = emptyMap(),
    ): Harness {
        val task = TaskRun(
            id = 7,
            requestText = "recovery test",
            startedAt = 1,
            completedAt = null,
            status = status,
            scanSnapshotId = null,
            planJson = DurablePlanCodec.encode("recovery test", listOf(operation)),
            summary = null,
            scopeRootRef = "/Download",
            storageAccessMode = StorageAccessMode.DIRECT,
            undoCompletedAt = null,
        )
        return Harness(
            FakeTaskRunDao(task),
            FakeMutationDao(record),
            FakeGateway(existing, bytesByRef),
        )
    }

    private class Harness(
        private val taskDao: FakeTaskRunDao,
        private val mutationDao: FakeMutationDao,
        gateway: FakeGateway,
    ) {
        private val recovery = MutationRecovery(mutationDao, taskDao) { gateway }

        val record: MutationRecord get() = mutationDao.records.single()
        val task: TaskRun get() = taskDao.current

        suspend fun recover() = recovery.recoverAll()
    }

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
        private val bytesByRef: Map<FileRef, ByteArray> = emptyMap(),
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
            isDirectory = ref == FileRef.Direct("/Download/Generated"),
            isHidden = false,
        )

        override suspend fun exists(ref: FileRef): Boolean = ref in existing
        override suspend fun openRead(ref: FileRef): InputStream =
            ByteArrayInputStream(bytesByRef[ref] ?: byteArrayOf(1))
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
