package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileScope
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
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.plan.SourcePrecondition
import com.pocketsteward.app.plan.SourcePreconditions
import com.pocketsteward.app.plan.ValidatedPlan
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.knownParentOrNull
import com.pocketsteward.app.storage.child
import kotlinx.coroutines.CancellationException

data class ExecutionSummary(
    val taskRunId: Long,
    val foldersCreated: Int,
    val filesMoved: Int,
    val filesCopied: Int,
    val filesRenamed: Int,
    val filesTrashed: Int,
    val filesWritten: Int,
    val failed: Int,
    val leftUntouched: List<RejectedOperation>,
    /**
     * Every operation that failed, with the error the gateway gave. The
     * count alone was in the journal and nowhere on screen, so "1 failed" on
     * a 4,835-operation run named neither the file nor the reason.
     */
    val failures: List<OperationFailure> = emptyList(),
    /**
     * M7 item 4. The paths of folders this run actually created, in the order
     * it created them.
     *
     * `[device]` A run reported "4 folder(s) created" and nothing anywhere
     * named them. The data was already in the journal on every
     * `CreateDirectory` record's `destinationAfter`, and the manifest already
     * read it back — the count was simply the only thing that left the
     * executor.
     *
     * Only folders that were genuinely created: an idempotent create over an
     * existing directory is a no-op owned by nobody, which is the same
     * distinction `MutationResult.Success.changed` draws for undo.
     */
    val createdFolders: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    val succeededTotal: Int get() =
        foldersCreated + filesMoved + filesCopied + filesRenamed + filesTrashed + filesWritten
}

/** One failed operation, in the shape the completion screen and Task history both need. */
data class OperationFailure(
    val sequence: Int,
    val operationType: MutationOperationType,
    val subject: String,
    val reason: String,
)

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
    /**
     * Revalidates the user's selected operations and durably records the
     * exact approved task before a foreground runner is allowed to start.
     *
     * Nothing executes here. If the filesystem snapshot has drifted enough
     * for validation to reject anything, fail closed and send the user back
     * through preview rather than silently enqueueing a smaller task.
     */
    suspend fun enqueueApproved(
        plan: AgentPlan,
        scopeRootRef: String,
        storageAccessMode: StorageAccessMode,
        index: FileIndex? = null,
    ): Long {
        require(plan.operations.isNotEmpty()) { "Cannot enqueue an empty plan." }
        require(taskRunDao.getRunning().isEmpty()) {
            "Another Pocket Steward file task is already running. Pause or finish it before starting another."
        }

        val effectiveIndex = index ?: InMemoryFileIndex(fileRecordDao.getAllUnderScopeRoot(scopeRootRef))
        val validated = PlanValidator.validate(plan.operations, effectiveIndex)
        require(validated.rejected.isEmpty()) {
            "Filesystem state changed since preview; rebuild the proposal before running it."
        }
        require(validated.accepted.size == plan.operations.size) {
            "Approved task validation did not preserve every selected operation."
        }

        val sourcePreconditions = captureApprovalPreconditions(validated.accepted)

        val startedAt = System.currentTimeMillis()
        return taskRunDao.insert(
            TaskRun(
                requestText = plan.goal,
                startedAt = startedAt,
                completedAt = null,
                status = TaskRunStatus.RUNNING,
                scanSnapshotId = null,
                planJson = DurablePlanCodec.encode(plan.goal, validated.accepted, sourcePreconditions),
                summary = "Queued · 0 of ${validated.accepted.size} operations",
                scopeRootRef = scopeRootRef,
                storageAccessMode = storageAccessMode,
                undoCompletedAt = null,
            ),
        )
    }

    /**
     * [onProgress] fires after each accepted operation resolves, so a caller
     * can show a moving count instead of a frozen screen while real files are
     * being moved. Purely advisory: correctness never depends on anyone
     * listening, and the write-ahead journal still governs what actually
     * happened if this is interrupted partway (plan Section 18).
     */
    suspend fun execute(
        plan: AgentPlan,
        scopeRootRef: String,
        storageAccessMode: StorageAccessMode,
        // Milestone 6 spec 6c: a plan can now target a folder the scanner
        // has never walked, and for those the scan index is empty — the
        // re-validation below would reject every operation with "parent does
        // not exist in the index". The caller supplies the index it validated
        // against in that case. Left null, this behaves exactly as before.
        index: FileIndex? = null,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ExecutionSummary {
        val effectiveIndex = index ?: InMemoryFileIndex(fileRecordDao.getAllUnderScopeRoot(scopeRootRef))
        val validated = PlanValidator.validate(plan.operations, effectiveIndex)
        val sourcePreconditions = captureApprovalPreconditions(validated.accepted)

        val startedAt = System.currentTimeMillis()
        val taskRunId = taskRunDao.insert(
            TaskRun(
                requestText = plan.goal,
                startedAt = startedAt,
                completedAt = null,
                status = TaskRunStatus.RUNNING,
                scanSnapshotId = null,
                planJson = DurablePlanCodec.encode(plan.goal, validated.accepted, sourcePreconditions),
                summary = null,
                scopeRootRef = scopeRootRef,
                storageAccessMode = storageAccessMode,
                undoCompletedAt = null,
            ),
        )

        var foldersCreated = 0
        var filesMoved = 0
        var filesCopied = 0
        var filesRenamed = 0
        var filesTrashed = 0
        var filesWritten = 0
        var failed = 0
        val failures = mutableListOf<OperationFailure>()
        val createdFolders = mutableListOf<String>()

        validated.accepted.forEachIndexed { sequence, operation ->
            val approvalFailure = approvalPreconditionFailure(
                operation = operation,
                expected = sourcePreconditions[sequence],
            )
            if (approvalFailure != null) {
                recordPreflightFailure(taskRunId, sequence, operation, approvalFailure)
                failures += operation.toFailure(sequence, approvalFailure)
                failed++
                onProgress(sequence + 1, validated.accepted.size)
                return@forEachIndexed
            }

            val expectedDestination = try {
                expectedDestination(operation)
            } catch (t: Throwable) {
                val reason = t.message ?: t.javaClass.simpleName
                recordPreflightFailure(taskRunId, sequence, operation, reason)
                failures += operation.toFailure(sequence, reason)
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
                val reason = "A file already occupies the requested directory path."
                recordPreflightFailure(taskRunId, sequence, operation, reason, expectedDestination)
                failures += operation.toFailure(sequence, reason)
                failed++
                return@forEachIndexed
            }

            val journalSource = try {
                journalSourceBefore(operation)
            } catch (t: Throwable) {
                val reason = "Could not capture a durable original location before mutation: ${t.message ?: t.javaClass.simpleName}"
                recordPreflightFailure(taskRunId, sequence, operation, reason, expectedDestination)
                failures += operation.toFailure(sequence, reason)
                failed++
                onProgress(sequence + 1, validated.accepted.size)
                return@forEachIndexed
            }

            val contentFingerprint = try {
                journalFingerprint(operation)
            } catch (t: Throwable) {
                val reason = "Could not prove source content before mutation: ${t.message ?: t.javaClass.simpleName}"
                recordPreflightFailure(taskRunId, sequence, operation, reason, expectedDestination)
                failures += operation.toFailure(sequence, reason)
                failed++
                onProgress(sequence + 1, validated.accepted.size)
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
                    contentFingerprint = contentFingerprint,
                    sourceBefore = journalSource,
                ),
            )

            when (val result = runOne(operation, contentFingerprint, expectedDestination)) {
                is MutationResult.Success -> {
                    val committed = MutationRecord(
                        id = mutationId,
                        taskRunId = taskRunId,
                        sequence = sequence,
                        operationType = operation.toOperationType(),
                        sourceBefore = FileRefJournalCodec.encode(journalSource),
                        destinationAfter = FileRefJournalCodec.encode(result.resultRef),
                        sourceFingerprint = contentFingerprint,
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
                            is PlannedOperation.CreateDirectory -> {
                                foldersCreated++
                                createdFolders += result.resultRef.rawValue()
                            }
                            is PlannedOperation.Move -> filesMoved++
                            is PlannedOperation.Copy -> filesCopied++
                            is PlannedOperation.Rename -> filesRenamed++
                            is PlannedOperation.Trash -> filesTrashed++
                            is PlannedOperation.WriteTextFile -> filesWritten++
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
                            sourceBefore = FileRefJournalCodec.encode(journalSource),
                            destinationAfter = expectedDestination?.let(FileRefJournalCodec::encode),
                            sourceFingerprint = contentFingerprint,
                            status = MutationStatus.FAILED,
                            executedAt = System.currentTimeMillis(),
                            undoState = UndoState.NOT_AVAILABLE,
                            undoAttemptedAt = null,
                            error = result.reason,
                            undoError = null,
                        ),
                    )
                    failures += operation.toFailure(sequence, result.reason)
                    failed++
                }
            }
            onProgress(sequence + 1, validated.accepted.size)
        }
        // The loop's early-return paths (preflight failure, idempotent
        // create) skip their own progress call. The count is absolute rather
        // than incremental, so the next operation corrects it — but if the
        // *last* operation took one of those paths, nothing would. This makes
        // the final number right regardless of which path ended the run.
        onProgress(validated.accepted.size, validated.accepted.size)

        val summary = ExecutionSummary(
            taskRunId = taskRunId,
            foldersCreated = foldersCreated,
            filesMoved = filesMoved,
            filesCopied = filesCopied,
            filesRenamed = filesRenamed,
            filesTrashed = filesTrashed,
            filesWritten = filesWritten,
            failed = failed,
            leftUntouched = validated.rejected,
            failures = failures,
            createdFolders = createdFolders,
        )

        val finished = taskRunDao.getById(taskRunId) ?: error("Task run disappeared during execution")
        taskRunDao.update(
            finished.copy(
                completedAt = System.currentTimeMillis(),
                // Spec item 3. FAILED now means what it says: nothing landed.
                // A run that moved 4,829 files and missed one is PARTIAL, and
                // filing it as FAILED made an audit trail read like a disaster
                // directly above its own "4834 succeeded" summary line.
                status = when {
                    failed == 0 -> TaskRunStatus.COMPLETED
                    summary.succeededTotal > 0 -> TaskRunStatus.PARTIAL
                    else -> TaskRunStatus.FAILED
                },
                summary = "${summary.succeededTotal} succeeded ($foldersCreated folders, $filesMoved moved, " +
                    "$filesCopied copied, $filesRenamed renamed, $filesTrashed trashed, $filesWritten written), $failed failed, " +
                    "${validated.rejected.size} left untouched",
            ),
        )

        return summary
    }

    /**
     * Continues an interrupted durable task under the same taskRunId.
     *
     * MutationRecovery must resolve any PENDING row before this is called.
     * Resolved COMMITTED/FAILED rows are never replayed; execution begins at
     * the first sequence that has no journal row.
     */
    suspend fun resume(
        taskRunId: Long,
        shouldPause: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ExecutionSummary {
        var task = taskRunDao.getById(taskRunId) ?: error("Unknown task run: $taskRunId")
        require(task.status == TaskRunStatus.RUNNING || task.status == TaskRunStatus.CANCELLED) {
            "Task is not resumable: ${task.status}"
        }

        val durable = DurablePlanCodec.decodeOrNull(task.planJson)
            ?: error("This task predates durable plan manifests and cannot be resumed safely.")
        val operations = durable.operations
        val existingRecords = mutationRecordDao.getForTaskRun(taskRunId)

        require(existingRecords.none { it.status == MutationStatus.PENDING }) {
            "Task still has a PENDING mutation. Run mutation recovery before resuming."
        }
        require(existingRecords.none { it.status == MutationStatus.NEEDS_REVIEW }) {
            "Task has an ambiguous mutation and needs review before resuming."
        }
        require(existingRecords.map { it.sequence }.distinct().size == existingRecords.size) {
            "Task journal contains duplicate operation sequences."
        }
        require(existingRecords.all { it.sequence in operations.indices }) {
            "Task journal contains a sequence outside the durable plan."
        }

        task = task.copy(
            status = TaskRunStatus.RUNNING,
            completedAt = null,
        )
        taskRunDao.update(task)

        var foldersCreated = 0
        var filesMoved = 0
        var filesCopied = 0
        var filesRenamed = 0
        var filesTrashed = 0
        var filesWritten = 0
        var failed = 0
        val failures = mutableListOf<OperationFailure>()
        val createdFolders = mutableListOf<String>()
        val existingBySequence = existingRecords.associateBy { it.sequence }

        fun countCommitted(sequence: Int, record: MutationRecord) {
            if (record.status != MutationStatus.COMMITTED || record.undoState != UndoState.AVAILABLE) return
            val operation = operations[sequence]
            when (operation) {
                is PlannedOperation.CreateDirectory -> {
                    foldersCreated++
                    record.destinationAfter
                        ?.let(FileRefJournalCodec::decode)
                        ?.rawValue()
                        ?.let(createdFolders::add)
                }
                is PlannedOperation.Move -> filesMoved++
                is PlannedOperation.Copy -> filesCopied++
                is PlannedOperation.Rename -> filesRenamed++
                is PlannedOperation.Trash -> filesTrashed++
                is PlannedOperation.WriteTextFile -> filesWritten++
            }
        }

        for (record in existingRecords) {
            when (record.status) {
                MutationStatus.COMMITTED -> countCommitted(record.sequence, record)
                MutationStatus.FAILED -> {
                    val reason = record.error ?: "Operation failed before interruption."
                    failures += operations[record.sequence].toFailure(record.sequence, reason)
                    failed++
                }
                MutationStatus.UNDONE -> error("A task with undone operations cannot be resumed.")
                MutationStatus.PENDING,
                MutationStatus.NEEDS_REVIEW,
                -> error("Task journal is not in a resumable state.")
            }
        }

        fun summary(cancelled: Boolean = false): ExecutionSummary = ExecutionSummary(
            taskRunId = taskRunId,
            foldersCreated = foldersCreated,
            filesMoved = filesMoved,
            filesCopied = filesCopied,
            filesRenamed = filesRenamed,
            filesTrashed = filesTrashed,
            filesWritten = filesWritten,
            failed = failed,
            leftUntouched = emptyList(),
            failures = failures.toList(),
            createdFolders = createdFolders.toList(),
            cancelled = cancelled,
        )

        onProgress(existingBySequence.size, operations.size)

        for ((sequence, operation) in operations.withIndex()) {
            if (sequence in existingBySequence) continue

            if (shouldPause()) {
                val paused = summary(cancelled = true)
                val latest = taskRunDao.getById(taskRunId) ?: task
                taskRunDao.update(
                    latest.copy(
                        status = TaskRunStatus.CANCELLED,
                        completedAt = System.currentTimeMillis(),
                        summary = "Paused after ${paused.succeededTotal + paused.failed} of ${operations.size} operations.",
                    ),
                )
                return paused
            }

            val approvalFailure = approvalPreconditionFailure(
                operation = operation,
                expected = durable.sourcePreconditions[sequence],
            )
            if (approvalFailure != null) {
                recordPreflightFailure(taskRunId, sequence, operation, approvalFailure)
                failures += operation.toFailure(sequence, approvalFailure)
                failed++
                onProgress(sequence + 1, operations.size)
                continue
            }
            val expectedDestination = try {
                expectedDestination(operation)
            } catch (t: Throwable) {
                val reason = t.message ?: t.javaClass.simpleName
                recordPreflightFailure(taskRunId, sequence, operation, reason)
                failures += operation.toFailure(sequence, reason)
                failed++
                onProgress(sequence + 1, operations.size)
                continue
            }

            if (operation is PlannedOperation.CreateDirectory &&
                expectedDestination != null &&
                gateway.exists(expectedDestination)
            ) {
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
                    onProgress(sequence + 1, operations.size)
                    continue
                }
                val reason = "A file already occupies the requested directory path."
                recordPreflightFailure(taskRunId, sequence, operation, reason, expectedDestination)
                failures += operation.toFailure(sequence, reason)
                failed++
                onProgress(sequence + 1, operations.size)
                continue
            }

            val journalSource = try {
                journalSourceBefore(operation)
            } catch (t: Throwable) {
                val reason = "Could not capture a durable original location before mutation: ${t.message ?: t.javaClass.simpleName}"
                recordPreflightFailure(taskRunId, sequence, operation, reason, expectedDestination)
                failures += operation.toFailure(sequence, reason)
                failed++
                onProgress(sequence + 1, operations.size)
                continue
            }

            val contentFingerprint = try {
                journalFingerprint(operation)
            } catch (t: Throwable) {
                val reason = "Could not prove source content before mutation: ${t.message ?: t.javaClass.simpleName}"
                recordPreflightFailure(taskRunId, sequence, operation, reason, expectedDestination)
                failures += operation.toFailure(sequence, reason)
                failed++
                onProgress(sequence + 1, operations.size)
                continue
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
                    contentFingerprint = contentFingerprint,
                    sourceBefore = journalSource,
                ),
            )

            when (val result = runOne(operation, contentFingerprint, expectedDestination)) {
                is MutationResult.Success -> {
                    val committed = MutationRecord(
                        id = mutationId,
                        taskRunId = taskRunId,
                        sequence = sequence,
                        operationType = operation.toOperationType(),
                        sourceBefore = FileRefJournalCodec.encode(journalSource),
                        destinationAfter = FileRefJournalCodec.encode(result.resultRef),
                        sourceFingerprint = contentFingerprint,
                        status = MutationStatus.COMMITTED,
                        executedAt = System.currentTimeMillis(),
                        undoState = if (result.changed) UndoState.AVAILABLE else UndoState.NOT_AVAILABLE,
                        undoAttemptedAt = null,
                        error = null,
                        undoError = null,
                    )
                    mutationRecordDao.update(committed)
                    if (result.changed) {
                        reindexAfterMutation(operation, result.resultRef, task.scopeRootRef)
                        when (operation) {
                            is PlannedOperation.CreateDirectory -> {
                                foldersCreated++
                                createdFolders += result.resultRef.rawValue()
                            }
                            is PlannedOperation.Move -> filesMoved++
                            is PlannedOperation.Copy -> filesCopied++
                            is PlannedOperation.Rename -> filesRenamed++
                            is PlannedOperation.Trash -> filesTrashed++
                            is PlannedOperation.WriteTextFile -> filesWritten++
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
                            sourceBefore = FileRefJournalCodec.encode(journalSource),
                            destinationAfter = expectedDestination?.let(FileRefJournalCodec::encode),
                            sourceFingerprint = contentFingerprint,
                            status = MutationStatus.FAILED,
                            executedAt = System.currentTimeMillis(),
                            undoState = UndoState.NOT_AVAILABLE,
                            undoAttemptedAt = null,
                            error = result.reason,
                            undoError = null,
                        ),
                    )
                    failures += operation.toFailure(sequence, result.reason)
                    failed++
                }
            }
            onProgress(sequence + 1, operations.size)
        }

        val finishedSummary = summary()
        val latest = taskRunDao.getById(taskRunId) ?: task
        taskRunDao.update(
            latest.copy(
                completedAt = System.currentTimeMillis(),
                status = when {
                    failed == 0 -> TaskRunStatus.COMPLETED
                    finishedSummary.succeededTotal > 0 -> TaskRunStatus.PARTIAL
                    else -> TaskRunStatus.FAILED
                },
                summary = "${finishedSummary.succeededTotal} succeeded ($foldersCreated folders, $filesMoved moved, " +
                    "$filesCopied copied, $filesRenamed renamed, $filesTrashed trashed, $filesWritten written), $failed failed",
            ),
        )
        onProgress(operations.size, operations.size)
        return finishedSummary
    }

    private suspend fun captureApprovalPreconditions(
        operations: List<PlannedOperation>,
    ): Map<Int, SourcePrecondition> {
        val result = linkedMapOf<Int, SourcePrecondition>()
        for ((sequence, operation) in operations.withIndex()) {
            val source = operation.preconditionSource() ?: continue
            if (!gateway.exists(source)) continue

            val current = SourcePreconditions.from(gateway.stat(source)) ?: continue
            val indexed = fileRecordDao.getByStableRef(source.rawValue())
                ?.let { SourcePreconditions.from(it) }
            if (indexed != null && !SourcePreconditions.matches(indexed, current)) {
                error("Source changed since the scan and must be reviewed again: ${source.rawValue()}")
            }
            result[sequence] = current
        }
        return result
    }

    private suspend fun approvalPreconditionFailure(
        operation: PlannedOperation,
        expected: SourcePrecondition?,
    ): String? {
        if (expected == null) return null
        val source = operation.preconditionSource()
            ?: return "Approved source precondition has no source operation."
        if (!gateway.exists(source)) {
            return "Source disappeared after approval; refusing to apply the operation: ${source.rawValue()}"
        }
        val current = SourcePreconditions.from(gateway.stat(source))
            ?: return "Source type changed after approval; refusing to apply the operation: ${source.rawValue()}"
        return if (SourcePreconditions.matches(expected, current)) {
            null
        } else {
            "Source changed after approval; refusing to apply the operation: ${source.rawValue()}"
        }
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
        contentFingerprint: String? = operation.fingerprintOrNull(),
        sourceBefore: FileRef = operation.sourceRef(),
    ): MutationRecord = MutationRecord(
        taskRunId = taskRunId,
        sequence = sequence,
        operationType = operation.toOperationType(),
        sourceBefore = FileRefJournalCodec.encode(sourceBefore),
        destinationAfter = destination?.let(FileRefJournalCodec::encode),
        sourceFingerprint = contentFingerprint,
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
        is PlannedOperation.Copy -> operation.destination
        is PlannedOperation.Rename -> renameDestinationForJournal(operation.source, operation.newName)
        is PlannedOperation.Trash -> gateway.trashDestination(operation.source)
        is PlannedOperation.WriteTextFile -> childRef(operation.parent, operation.name)
    }

    private suspend fun journalFingerprint(operation: PlannedOperation): String? = when (operation) {
        is PlannedOperation.Copy -> StorageDigest.sha256(gateway, operation.source)
        is PlannedOperation.WriteTextFile -> StorageDigest.sha256(operation.content)
        is PlannedOperation.Move ->
            if (operation.source is FileRef.Direct) null else StorageDigest.sha256(gateway, operation.source)
        is PlannedOperation.Rename ->
            if (operation.source is FileRef.Direct) null else StorageDigest.sha256(gateway, operation.source)
        is PlannedOperation.Trash -> operation.sourceFingerprint
            ?: if (operation.source is FileRef.Direct) null else StorageDigest.sha256(gateway, operation.source)
        is PlannedOperation.CreateDirectory -> null
    }

    private suspend fun journalSourceBefore(operation: PlannedOperation): FileRef {
        val source = operation.sourceRef()
        if (source !is FileRef.Saf) return source

        if (operation is PlannedOperation.CreateDirectory ||
            operation is PlannedOperation.WriteTextFile
        ) {
            return source
        }

        val record = fileRecordDao.getByStableRef(source.rawValue())
            ?: error("SAF source is not present in the current scan index.")
        val parent = record.parentRef?.let(::parseFileRef)
            ?: error("SAF source has no indexed parent; exact undo cannot be guaranteed.")
        return FileRef.Child(parent, record.displayName)
    }

    private suspend fun renameDestinationForJournal(
        source: FileRef,
        newName: String,
    ): FileRef = when (source) {
        is FileRef.Direct -> {
            val parent = source.knownParentOrNull()
                ?: error("Cannot determine rename parent.")
            parent.child(newName)
        }
        is FileRef.Child -> source.parent.child(newName)
        is FileRef.Saf -> {
            val record = fileRecordDao.getByStableRef(source.rawValue())
                ?: error("SAF rename source is not present in the current scan index.")
            val parent = record.parentRef?.let(::parseFileRef)
                ?: error("SAF rename source has no indexed parent.")
            parent.child(newName)
        }
    }

    private suspend fun runOne(
        operation: PlannedOperation,
        contentFingerprint: String?,
        expectedDestination: FileRef?,
    ): MutationResult = try {
        when (operation) {
            is PlannedOperation.CreateDirectory -> gateway.createDirectory(operation.parent, operation.name)
            is PlannedOperation.Move -> {
                val moved = gateway.move(operation.source, operation.destination)
                if (contentFingerprint != null) {
                    verifyCreatedContent(moved, contentFingerprint, "Moved")
                } else {
                    moved
                }
            }
            is PlannedOperation.Copy -> verifyCreatedContent(
                result = gateway.copy(operation.source, operation.destination),
                expectedFingerprint = contentFingerprint,
                action = "Copied",
            )
            is PlannedOperation.Rename -> {
                if (operation.source is FileRef.Direct) {
                    gateway.rename(operation.source, operation.newName)
                } else {
                    val destination = requireNotNull(expectedDestination) {
                        "SAF rename has no durable destination."
                    }
                    verifyCreatedContent(
                        gateway.move(operation.source, destination),
                        contentFingerprint,
                        "Renamed",
                    )
                }
            }
            is PlannedOperation.Trash -> {
                if (contentFingerprint != null) {
                    val actual = StorageDigest.sha256(gateway, operation.source)
                    if (!actual.equals(contentFingerprint, ignoreCase = true)) {
                        MutationResult.Failure(
                            "Source content changed since duplicate review; refusing to move it to Trash.",
                        )
                    } else {
                        gateway.trash(operation.source)
                    }
                } else {
                    gateway.trash(operation.source)
                }
            }
            is PlannedOperation.WriteTextFile -> verifyCreatedContent(
                result = gateway.writeTextFile(operation.parent, operation.name, operation.content),
                expectedFingerprint = contentFingerprint,
                action = "Written",
            )
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        MutationResult.Failure(t.message ?: t.javaClass.simpleName, t)
    }

    private suspend fun verifyCreatedContent(
        result: MutationResult,
        expectedFingerprint: String?,
        action: String,
    ): MutationResult {
        if (result !is MutationResult.Success || !result.changed || expectedFingerprint == null) {
            return result
        }

        val actual = runCatching { StorageDigest.sha256(gateway, result.resultRef) }.getOrNull()
        if (actual != null && actual.equals(expectedFingerprint, ignoreCase = true)) {
            return result
        }

        val quarantine = runCatching { gateway.trash(result.resultRef) }.getOrNull()
        val cleanup = when (quarantine) {
            is MutationResult.Success -> "The unverified output was moved to PocketSteward Trash."
            is MutationResult.Failure -> "The unverified output could not be quarantined: ${quarantine.reason}"
            null -> "The unverified output could not be quarantined."
        }
        return MutationResult.Failure(
            "$action content could not be verified against the approved SHA-256. $cleanup",
        )
    }

    private suspend fun reindexAfterMutation(
        operation: PlannedOperation,
        newRef: FileRef,
        scopeRootRef: String,
    ) {
        val knownScopes = (fileRecordDao.getKnownScopeRoots() + scopeRootRef).distinct()
        val sourceRecord = when (operation) {
            is PlannedOperation.Move,
            is PlannedOperation.Copy,
            is PlannedOperation.Rename,
            is PlannedOperation.Trash,
            -> fileRecordDao.getByStableRef(operation.sourceRef().rawValue())
            is PlannedOperation.CreateDirectory,
            is PlannedOperation.WriteTextFile,
            -> null
        }

        val destinationParent = when (operation) {
            is PlannedOperation.CreateDirectory -> operation.parent
            is PlannedOperation.WriteTextFile -> operation.parent
            is PlannedOperation.Move -> operation.destination.knownParentOrNull()
            is PlannedOperation.Copy -> operation.destination.knownParentOrNull()
            is PlannedOperation.Rename ->
                sourceRecord?.parentRef?.let(::parseFileRef)
            is PlannedOperation.Trash -> null
        }

        if (operation is PlannedOperation.Trash) {
            fileRecordDao.deleteByStableRef(operation.source.rawValue())
            return
        }

        val meta = gateway.stat(newRef)

        if (operation is PlannedOperation.Copy) {
            val copied = (sourceRecord ?: meta.toFileRecord(destinationParent)).copy(
                id = 0,
                stableRef = newRef.rawValue(),
                displayName = meta.displayName,
                extension = meta.extension,
                mimeType = meta.mimeType,
                absolutePathOrUri = newRef.rawValue(),
                parentRef = destinationParent?.rawValue(),
                sizeBytes = meta.sizeBytes,
                modifiedAt = meta.modifiedAtEpochMs,
                lastScannedAt = System.currentTimeMillis(),
                isDirectory = meta.isDirectory,
                isHidden = meta.isHidden,
            )
            fileRecordDao.upsert(copied)
            val scopes = matchingScopeRoots(newRef, knownScopes).toMutableSet()
            if (newRef is FileRef.Saf) scopes += scopeRootRef
            fileRecordDao.insertScopeTags(
                scopes.map { FileScope(copied.stableRef, it) },
            )
            return
        }

        if (operation is PlannedOperation.CreateDirectory ||
            operation is PlannedOperation.WriteTextFile
        ) {
            val record = meta.toFileRecord(destinationParent)
            fileRecordDao.upsert(record)
            val scopes = matchingScopeRoots(newRef, knownScopes).toMutableSet()
            if (newRef is FileRef.Saf) scopes += scopeRootRef
            fileRecordDao.insertScopeTags(
                scopes.map { FileScope(record.stableRef, it) },
            )
            return
        }

        val oldStableRef = operation.sourceRef().rawValue()
        fileRecordDao.deleteByStableRef(oldStableRef)

        val moved = (sourceRecord ?: meta.toFileRecord(destinationParent)).copy(
            stableRef = newRef.rawValue(),
            displayName = meta.displayName,
            extension = meta.extension,
            mimeType = meta.mimeType,
            absolutePathOrUri = newRef.rawValue(),
            parentRef = destinationParent?.rawValue(),
            sizeBytes = meta.sizeBytes,
            modifiedAt = meta.modifiedAtEpochMs,
            lastScannedAt = System.currentTimeMillis(),
            isDirectory = meta.isDirectory,
            isHidden = meta.isHidden,
        )
        fileRecordDao.upsert(moved)
        val scopes = matchingScopeRoots(newRef, knownScopes).toMutableSet()
        if (newRef is FileRef.Saf) scopes += scopeRootRef
        fileRecordDao.insertScopeTags(
            scopes.map { FileScope(newRef.rawValue(), it) },
        )
    }

    /**
     * The durable record of what was *asked for*, beside the journal's record
     * of what happened.
     *
     * The accepted lines are numbered with the same sequence the journal
     * uses, which is the index into `validated.accepted` — not into the
     * original plan, because a rejected operation never gets a journal row
     * and numbering from the raw plan would silently shift every line after
     * the first rejection. A manifest built later joins on that number to
     * recover each operation's stated reason, which is where a
     * duplicate-trash names the copy it kept.
     *
     * Tab-separated rather than JSON because the reason text is free-form and
     * this has to survive being read by a human in a text editor.
     */
    private fun describePlan(goal: String, validated: ValidatedPlan): String = buildString {
        appendLine(goal)
        validated.accepted.forEachIndexed { sequence, operation ->
            appendLine("$sequence\t${operation.toOperationType()}\t${operation.reason}")
        }
        if (validated.rejected.isNotEmpty()) {
            appendLine("# left untouched")
            validated.rejected.forEach { rejected ->
                appendLine("-\t${rejected.operation.toOperationType()}\t${rejected.reason}")
            }
        }
    }
}

private fun PlannedOperation.preconditionSource(): FileRef? = when (this) {
    is PlannedOperation.Move -> source
    is PlannedOperation.Copy -> source
    is PlannedOperation.Rename -> source
    is PlannedOperation.Trash -> source
    is PlannedOperation.CreateDirectory,
    is PlannedOperation.WriteTextFile,
    -> null
}
private fun PlannedOperation.sourceRef(): FileRef = when (this) {
    is PlannedOperation.CreateDirectory -> parent
    is PlannedOperation.Move -> source
    is PlannedOperation.Copy -> source
    is PlannedOperation.Rename -> source
    is PlannedOperation.Trash -> source
    // The parent, same as CreateDirectory: the thing that existed before.
    is PlannedOperation.WriteTextFile -> parent
}

private fun PlannedOperation.toOperationType(): MutationOperationType = when (this) {
    is PlannedOperation.CreateDirectory -> MutationOperationType.CREATE_DIRECTORY
    is PlannedOperation.Move -> MutationOperationType.MOVE
    is PlannedOperation.Copy -> MutationOperationType.COPY
    is PlannedOperation.Rename -> MutationOperationType.RENAME
    is PlannedOperation.Trash -> MutationOperationType.TRASH
    is PlannedOperation.WriteTextFile -> MutationOperationType.WRITE_TEXT_FILE
}

private fun PlannedOperation.fingerprintOrNull(): String? =
    (this as? PlannedOperation.Trash)?.sourceFingerprint

/** The path or name a person would recognise this operation by, for a failure list. */
private fun PlannedOperation.toFailure(sequence: Int, reason: String): OperationFailure = OperationFailure(
    sequence = sequence,
    operationType = toOperationType(),
    subject = when (this) {
        is PlannedOperation.CreateDirectory -> "${parent.rawValue().trimEnd('/')}/$name"
        is PlannedOperation.Move -> source.rawValue()
        is PlannedOperation.Copy -> source.rawValue()
        is PlannedOperation.Rename -> source.rawValue()
        is PlannedOperation.Trash -> source.rawValue()
        is PlannedOperation.WriteTextFile -> "${parent.rawValue().trimEnd('/')}/$name"
    },
    reason = reason,
)

private fun childRef(parent: FileRef, name: String): FileRef =
    parent.child(name)

private fun matchingScopeRoots(ref: FileRef, knownScopes: List<String>): List<String> {
    val raw = ref.rawValue().trimEnd('/')
    return knownScopes.distinct().filter { scope ->
        val normalized = scope.trimEnd('/')
        raw == normalized || raw.startsWith("$normalized/")
    }
}

private fun FileRef.parentRefOrNull(): FileRef? =
    knownParentOrNull()

private fun FileMetadata.toFileRecord(parent: FileRef?): FileRecord = FileRecord(
    stableRef = ref.rawValue(),
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
