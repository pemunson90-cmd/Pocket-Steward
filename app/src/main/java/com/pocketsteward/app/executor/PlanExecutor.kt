package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.MutationOperationType
import com.pocketsteward.app.data.db.MutationRecord
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.data.db.UndoState
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue

data class ExecutionSummary(
    val taskRunId: Long,
    val foldersCreated: Int,
    val filesMoved: Int,
    val filesRenamed: Int,
    val filesTrashed: Int,
    val failed: Int,
    val leftUntouched: List<RejectedOperation>,
) {
    val succeededTotal: Int get() = foldersCreated + filesMoved + filesRenamed + filesTrashed
}

/**
 * Deterministic mutation executor. Every real filesystem change is preceded
 * by a durable PENDING journal row containing both the pre-operation ref and
 * the expected post-operation ref. Recovery therefore has enough facts to
 * determine whether an interrupted operation landed without asking a model.
 */
class PlanExecutor(
    private val gateway: StorageGateway,
    private val fileRecordDao: FileRecordDao,
    private val taskRunDao: TaskRunDao,
    private val mutationRecordDao: MutationRecordDao,
) {
    suspend fun execute(
        plan: AgentPlan,
        scopeRootRef: String,
        storageAccessMode: StorageAccessMode,
    ): ExecutionSummary {
        val index = InMemoryFileIndex(fileRecordDao.getAllUnderScopeRoot(scopeRootRef))
        val validated = PlanValidator.validate(plan.operations, index)

        val startedAt = System.currentTimeMillis()
        val taskRunId = taskRunDao.insert(
            TaskRun(
                requestText = plan.goal,
                startedAt = startedAt,
                completedAt = null,
                status = TaskRunStatus.RUNNING,
                scanSnapshotId = null,
                planJson = describePlan(plan),
                summary = null,
                scopeRootRef = scopeRootRef,
                storageAccessMode = storageAccessMode,
                undoCompletedAt = null,
            ),
        )

        var foldersCreated = 0
        var filesMoved = 0
        var filesRenamed = 0
        var filesTrashed = 0
        var failed = 0

        validated.accepted.forEachIndexed { sequence, operation ->
            val expectedDestination = try {
                expectedDestination(operation)
            } catch (t: Throwable) {
                recordPreflightFailure(taskRunId, sequence, operation, t.message ?: t.javaClass.simpleName)
                failed++
                return@forEachIndexed
            }

            // CreateDirectory is intentionally idempotent, but an existing
            // directory is a no-op owned by nobody. Journal it as such before
            // moving on so Undo can never remove a directory we did not create.
            if (operation is PlannedOperation.CreateDirectory && expectedDestination != null && gateway.exists(expectedDestination)) {
                val existing = gateway.stat(expectedDestination)
                if (existing.isDirectory) {
                    mutationRecordDao.insert(
                        newRecord(
                            taskRunId = taskRunId,
                            sequence = sequence,
                            operation = operation,
                            destination = expectedDestination,
                            status = MutationStatus.COMMITTED,
                            executedAt = System.currentTimeMillis(),
                            undoState = UndoState.NOT_AVAILABLE,
                        ),
                    )
                    return@forEachIndexed
                }
                recordPreflightFailure(taskRunId, sequence, operation, "A file already occupies the requested directory path.", expectedDestination)
                failed++
                return@forEachIndexed
            }

            val mutationId = mutationRecordDao.insert(
                newRecord(
                    taskRunId = taskRunId,
                    sequence = sequence,
                    operation = operation,
                    destination = expectedDestination,
                    status = MutationStatus.PENDING,
                    executedAt = null,
                    undoState = UndoState.NOT_AVAILABLE,
                ),
            )

            when (val result = runOne(operation)) {
                is MutationResult.Success -> {
                    val committed = MutationRecord(
                        id = mutationId,
                        taskRunId = taskRunId,
                        sequence = sequence,
                        operationType = operation.toOperationType(),
                        sourceBefore = FileRefJournalCodec.encode(operation.sourceRef()),
                        destinationAfter = FileRefJournalCodec.encode(result.resultRef),
                        sourceFingerprint = null,
                        status = MutationStatus.COMMITTED,
                        executedAt = System.currentTimeMillis(),
                        undoState = if (result.changed) UndoState.AVAILABLE else UndoState.NOT_AVAILABLE,
                        undoAttemptedAt = null,
                        error = null,
                        undoError = null,
                    )
                    mutationRecordDao.update(committed)
                    if (result.changed) {
                        reindexAfterMutation(operation, result.resultRef, scopeRootRef)
                        when (operation) {
                            is PlannedOperation.CreateDirectory -> foldersCreated++
                            is PlannedOperation.Move -> filesMoved++
                            is PlannedOperation.Rename -> filesRenamed++
                            is PlannedOperation.Trash -> filesTrashed++
                        }
                    }
                }

                is MutationResult.Failure -> {
                    mutationRecordDao.update(
                        MutationRecord(
                            id = mutationId,
                            taskRunId = taskRunId,
                            sequence = sequence,
                            operationType = operation.toOperationType(),
                            sourceBefore = FileRefJournalCodec.encode(operation.sourceRef()),
                            destinationAfter = expectedDestination?.let(FileRefJournalCodec::encode),
                            sourceFingerprint = null,
                            status = MutationStatus.FAILED,
                            executedAt = System.currentTimeMillis(),
                            undoState = UndoState.NOT_AVAILABLE,
                            undoAttemptedAt = null,
                            error = result.reason,
                            undoError = null,
                        ),
                    )
                    failed++
                }
            }
        }

        val summary = ExecutionSummary(
            taskRunId,
            foldersCreated,
            filesMoved,
            filesRenamed,
            filesTrashed,
            failed,
            validated.rejected,
        )

        val finished = taskRunDao.getById(taskRunId) ?: error("Task run disappeared during execution")
        taskRunDao.update(
            finished.copy(
                completedAt = System.currentTimeMillis(),
                status = if (failed == 0) TaskRunStatus.COMPLETED else TaskRunStatus.FAILED,
                summary = "${summary.succeededTotal} succeeded ($foldersCreated folders, $filesMoved moved, " +
                    "$filesRenamed renamed, $filesTrashed trashed), $failed failed, ${validated.rejected.size} left untouched",
            ),
        )

        return summary
    }

    private suspend fun recordPreflightFailure(
        taskRunId: Long,
        sequence: Int,
        operation: PlannedOperation,
        reason: String,
        destination: FileRef? = null,
    ) {
        mutationRecordDao.insert(
            newRecord(
                taskRunId = taskRunId,
                sequence = sequence,
                operation = operation,
                destination = destination,
                status = MutationStatus.FAILED,
                executedAt = System.currentTimeMillis(),
                undoState = UndoState.NOT_AVAILABLE,
                error = reason,
            ),
        )
    }

    private fun newRecord(
        taskRunId: Long,
        sequence: Int,
        operation: PlannedOperation,
        destination: FileRef?,
        status: MutationStatus,
        executedAt: Long?,
        undoState: UndoState,
        error: String? = null,
    ): MutationRecord = MutationRecord(
        taskRunId = taskRunId,
        sequence = sequence,
        operationType = operation.toOperationType(),
        sourceBefore = FileRefJournalCodec.encode(operation.sourceRef()),
        destinationAfter = destination?.let(FileRefJournalCodec::encode),
        sourceFingerprint = null,
        status = status,
        executedAt = executedAt,
        undoState = undoState,
        undoAttemptedAt = null,
        error = error,
        undoError = null,
    )

    private suspend fun expectedDestination(operation: PlannedOperation): FileRef? = when (operation) {
        is PlannedOperation.CreateDirectory -> childRef(operation.parent, operation.name)
        is PlannedOperation.Move -> operation.destination
        is PlannedOperation.Rename -> renameDestination(operation.source, operation.newName)
        is PlannedOperation.Trash -> gateway.trashDestination(operation.source)
    }

    private suspend fun runOne(operation: PlannedOperation): MutationResult = try {
        when (operation) {
            is PlannedOperation.CreateDirectory -> gateway.createDirectory(operation.parent, operation.name)
            is PlannedOperation.Move -> gateway.move(operation.source, operation.destination)
            is PlannedOperation.Rename -> gateway.rename(operation.source, operation.newName)
            is PlannedOperation.Trash -> gateway.trash(operation.source)
        }
    } catch (t: Throwable) {
        MutationResult.Failure(t.message ?: t.javaClass.simpleName, t)
    }

    private suspend fun reindexAfterMutation(operation: PlannedOperation, newRef: FileRef, scopeRootRef: String) {
        if (operation is PlannedOperation.CreateDirectory) {
            fileRecordDao.upsert(gateway.stat(newRef).toFileRecord(scopeRootRef, newRef.parentRefOrNull()))
            return
        }

        val oldStableRef = operation.sourceRef().rawValue()
        val existing = fileRecordDao.getByStableRef(oldStableRef)
        fileRecordDao.deleteByStableRef(oldStableRef)

        if (operation is PlannedOperation.Trash) return

        val meta = gateway.stat(newRef)
        fileRecordDao.upsert(
            (existing ?: meta.toFileRecord(scopeRootRef, newRef.parentRefOrNull())).copy(
                stableRef = newRef.rawValue(),
                scopeRootRef = scopeRootRef,
                displayName = meta.displayName,
                extension = meta.extension,
                mimeType = meta.mimeType,
                absolutePathOrUri = newRef.rawValue(),
                parentRef = newRef.parentRefOrNull()?.rawValue(),
                sizeBytes = meta.sizeBytes,
                modifiedAt = meta.modifiedAtEpochMs,
                lastScannedAt = System.currentTimeMillis(),
                isDirectory = meta.isDirectory,
                isHidden = meta.isHidden,
            ),
        )
    }

    private fun describePlan(plan: AgentPlan): String =
        "${plan.goal}\n" + plan.operations.joinToString("\n") { "- $it" }
}

private fun PlannedOperation.sourceRef(): FileRef = when (this) {
    is PlannedOperation.CreateDirectory -> parent
    is PlannedOperation.Move -> source
    is PlannedOperation.Rename -> source
    is PlannedOperation.Trash -> source
}

private fun PlannedOperation.toOperationType(): MutationOperationType = when (this) {
    is PlannedOperation.CreateDirectory -> MutationOperationType.CREATE_DIRECTORY
    is PlannedOperation.Move -> MutationOperationType.MOVE
    is PlannedOperation.Rename -> MutationOperationType.RENAME
    is PlannedOperation.Trash -> MutationOperationType.TRASH
}

private fun childRef(parent: FileRef, name: String): FileRef = when (parent) {
    is FileRef.Direct -> FileRef.Direct("${parent.absolutePath.trimEnd('/')}/$name")
    is FileRef.Saf -> error("SAF mutations are not implemented yet")
}

private fun renameDestination(source: FileRef, newName: String): FileRef = when (source) {
    is FileRef.Direct -> {
        val parent = source.absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        require(parent.isNotBlank()) { "Cannot determine rename parent" }
        FileRef.Direct("${parent.trimEnd('/')}/$newName")
    }
    is FileRef.Saf -> error("SAF mutations are not implemented yet")
}

private fun FileRef.parentRefOrNull(): FileRef? = when (this) {
    is FileRef.Direct -> absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.let(FileRef::Direct)
    is FileRef.Saf -> null
}

private fun FileMetadata.toFileRecord(scopeRootRef: String, parent: FileRef?): FileRecord = FileRecord(
    stableRef = ref.rawValue(),
    scopeRootRef = scopeRootRef,
    displayName = displayName,
    extension = extension,
    mimeType = mimeType,
    absolutePathOrUri = ref.rawValue(),
    parentRef = parent?.rawValue(),
    sizeBytes = sizeBytes,
    createdAt = createdAtEpochMs,
    modifiedAt = modifiedAtEpochMs,
    lastScannedAt = System.currentTimeMillis(),
    isDirectory = isDirectory,
    isHidden = isHidden,
)
