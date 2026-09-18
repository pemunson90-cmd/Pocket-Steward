package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.MutationOperationType
import com.pocketsteward.app.data.db.MutationRecord
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.data.db.UndoState
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway

/**
 * Resolves write-ahead rows left PENDING by process death. Ambiguous states
 * are deliberately escalated to NEEDS_REVIEW/BLOCKED rather than guessed.
 */
class MutationRecovery(
    private val mutationRecordDao: MutationRecordDao,
    private val taskRunDao: TaskRunDao,
    private val gatewayFor: (StorageAccessMode) -> StorageGateway,
) {
    suspend fun recoverAll() {
        recoverForwardMutations()
        recoverUndoMutations()
        refreshTaskStatuses()
    }

    private suspend fun recoverForwardMutations() {
        for (record in mutationRecordDao.getAllPending()) {
            val task = taskRunDao.getById(record.taskRunId) ?: continue
            val gateway = gatewayFor(task.storageAccessMode)
            val source = FileRefJournalCodec.decode(record.sourceBefore)
            val destination = record.destinationAfter?.let(FileRefJournalCodec::decode)

            val recovered = if (destination == null) {
                record.copy(
                    status = MutationStatus.NEEDS_REVIEW,
                    error = "Interrupted operation has no expected destination; manual review required.",
                )
            } else {
                val sourceExists = gateway.exists(source)
                val destinationExists = gateway.exists(destination)
                when (record.operationType) {
                    MutationOperationType.CREATE_DIRECTORY -> when {
                        destinationExists -> record.copy(
                            status = MutationStatus.COMMITTED,
                            executedAt = record.executedAt ?: System.currentTimeMillis(),
                            undoState = UndoState.AVAILABLE,
                            error = "Recovered after interruption: created directory exists.",
                        )
                        else -> record.copy(
                            status = MutationStatus.FAILED,
                            executedAt = record.executedAt ?: System.currentTimeMillis(),
                            undoState = UndoState.NOT_AVAILABLE,
                            error = "Recovered after interruption: directory creation did not land.",
                        )
                    }
                    // A written file either landed or it didn't, and its
                    // source (the parent directory) was never going to
                    // change, so the source/destination reasoning the move
                    // cases use doesn't apply.
                    MutationOperationType.WRITE_TEXT_FILE -> when {
                        destinationExists -> record.copy(
                            status = MutationStatus.COMMITTED,
                            executedAt = record.executedAt ?: System.currentTimeMillis(),
                            undoState = UndoState.AVAILABLE,
                            error = "Recovered after interruption: written file exists.",
                        )
                        else -> record.copy(
                            status = MutationStatus.FAILED,
                            executedAt = record.executedAt ?: System.currentTimeMillis(),
                            undoState = UndoState.NOT_AVAILABLE,
                            error = "Recovered after interruption: the file was never written.",
                        )
                    }
                    MutationOperationType.MOVE,
                    MutationOperationType.RENAME,
                    MutationOperationType.TRASH,
                    -> when {
                        !sourceExists && destinationExists -> record.copy(
                            status = MutationStatus.COMMITTED,
                            executedAt = record.executedAt ?: System.currentTimeMillis(),
                            undoState = UndoState.AVAILABLE,
                            error = "Recovered after interruption: destination exists and source is gone.",
                        )
                        sourceExists && !destinationExists -> record.copy(
                            status = MutationStatus.FAILED,
                            executedAt = record.executedAt ?: System.currentTimeMillis(),
                            undoState = UndoState.NOT_AVAILABLE,
                            error = "Recovered after interruption: source remains and destination is absent.",
                        )
                        else -> record.copy(
                            status = MutationStatus.NEEDS_REVIEW,
                            undoState = UndoState.BLOCKED,
                            error = "Interrupted mutation is ambiguous (source/destination state cannot prove outcome).",
                        )
                    }
                    MutationOperationType.COPY -> record.copy(
                        status = MutationStatus.NEEDS_REVIEW,
                        undoState = UndoState.BLOCKED,
                        error = "Interrupted COPY requires manual review.",
                    )
                }
            }
            mutationRecordDao.update(recovered)
        }
    }

    private suspend fun recoverUndoMutations() {
        for (record in mutationRecordDao.getAllPendingUndo()) {
            val task = taskRunDao.getById(record.taskRunId) ?: continue
            val gateway = gatewayFor(task.storageAccessMode)
            val destinationAfter = record.destinationAfter?.let(FileRefJournalCodec::decode)
            if (destinationAfter == null) {
                mutationRecordDao.update(record.copy(undoState = UndoState.BLOCKED, undoError = "Undo journal has no destination."))
                continue
            }

            val recovered = when (record.operationType) {
                MutationOperationType.CREATE_DIRECTORY -> {
                    if (!gateway.exists(destinationAfter)) {
                        record.copy(status = MutationStatus.UNDONE, undoState = UndoState.UNDONE, undoError = null)
                    } else {
                        val children = runCatching { gateway.listChildren(destinationAfter) }.getOrNull()
                        if (children != null && children.isEmpty()) {
                            record.copy(undoState = UndoState.AVAILABLE, undoError = "Undo was interrupted before the empty directory was removed; safe to retry.")
                        } else {
                            record.copy(undoState = UndoState.BLOCKED, undoError = "Directory exists and is no longer provably safe to remove.")
                        }
                    }
                }
                // Undoing a write trashes the file. Gone from where it was
                // written means the trash landed; still there means it can
                // safely be retried, because trashing is idempotent in the
                // only direction that matters.
                MutationOperationType.WRITE_TEXT_FILE -> {
                    if (!gateway.exists(destinationAfter)) {
                        record.copy(status = MutationStatus.UNDONE, undoState = UndoState.UNDONE, undoError = null)
                    } else {
                        record.copy(
                            undoState = UndoState.AVAILABLE,
                            undoError = "Undo was interrupted before the written file was moved to Trash; safe to retry.",
                        )
                    }
                }
                MutationOperationType.MOVE,
                MutationOperationType.RENAME,
                MutationOperationType.TRASH,
                -> {
                    val original = FileRefJournalCodec.decode(record.sourceBefore)
                    val originalExists = gateway.exists(original)
                    val currentExists = gateway.exists(destinationAfter)
                    when {
                        originalExists && !currentExists -> record.copy(
                            status = MutationStatus.UNDONE,
                            undoState = UndoState.UNDONE,
                            undoError = null,
                        )
                        !originalExists && currentExists -> record.copy(
                            undoState = UndoState.AVAILABLE,
                            undoError = "Undo was interrupted before the inverse move landed; safe to retry.",
                        )
                        else -> record.copy(
                            undoState = UndoState.BLOCKED,
                            undoError = "Interrupted undo is ambiguous; refusing to guess.",
                        )
                    }
                }
                MutationOperationType.COPY -> record.copy(
                    undoState = UndoState.BLOCKED,
                    undoError = "COPY undo recovery is not implemented.",
                )
            }
            mutationRecordDao.update(recovered)
        }
    }

    private suspend fun refreshTaskStatuses() {
        for (task in taskRunDao.getRunsNeedingRecovery()) {
            val records = mutationRecordDao.getForTaskRun(task.id)
            val status = when {
                records.any { it.status == MutationStatus.NEEDS_REVIEW || it.undoState == UndoState.BLOCKED } ->
                    if (task.status == TaskRunStatus.UNDOING || task.status == TaskRunStatus.UNDO_PARTIAL) {
                        TaskRunStatus.UNDO_PARTIAL
                    } else {
                        TaskRunStatus.NEEDS_REVIEW
                    }
                task.status == TaskRunStatus.UNDOING && records.filter { it.undoState != UndoState.NOT_AVAILABLE }.all { it.undoState == UndoState.UNDONE } ->
                    TaskRunStatus.UNDONE
                task.status == TaskRunStatus.RUNNING && records.isEmpty() ->
                    TaskRunStatus.FAILED
                // Same distinction PlanExecutor makes at the end of a clean
                // run, applied to a run that died partway: a failure beside
                // committed work is PARTIAL, and only a run where nothing
                // landed is FAILED.
                task.status == TaskRunStatus.RUNNING && records.any { it.status == MutationStatus.FAILED } ->
                    if (records.any { it.status == MutationStatus.COMMITTED }) {
                        TaskRunStatus.PARTIAL
                    } else {
                        TaskRunStatus.FAILED
                    }
                task.status == TaskRunStatus.RUNNING && records.none { it.status == MutationStatus.PENDING } ->
                    TaskRunStatus.COMPLETED
                else -> task.status
            }
            if (status != task.status) {
                taskRunDao.update(
                    task.copy(
                        status = status,
                        completedAt = task.completedAt ?: System.currentTimeMillis(),
                        undoCompletedAt = if (status == TaskRunStatus.UNDONE) System.currentTimeMillis() else task.undoCompletedAt,
                    ),
                )
            }
        }
    }
}
