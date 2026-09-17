package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.MutationOperationType
import com.pocketsteward.app.data.db.MutationRecord
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
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
    /** Folder creations are not "moves" — kept separate so a completion screen doesn't conflate them. */
    val succeededTotal: Int get() = foldersCreated + filesMoved + filesRenamed + filesTrashed
}

/**
 * Plan Section 6/11: the deterministic executor. Takes an [AgentPlan],
 * validates it against the current index, and runs only the accepted
 * operations — this is the one place in the app allowed to call a
 * [StorageGateway] mutation method.
 *
 * Journaling follows [com.pocketsteward.app.scan.FileScanner]'s pattern:
 * each [MutationRecord] is written `PENDING` *before* the gateway call and
 * flipped to `COMMITTED`/`FAILED` only after, so a crash mid-operation
 * leaves a row reconciliation can find rather than an operation that
 * happened with no record of it. Computing and storing the *inverse* of
 * each operation, and an actual undo path, is Milestone 3 — this milestone
 * only has to execute safely and journal what it did.
 */
class PlanExecutor(
    private val gateway: StorageGateway,
    private val fileRecordDao: FileRecordDao,
    private val taskRunDao: TaskRunDao,
    private val mutationRecordDao: MutationRecordDao,
) {
    suspend fun execute(plan: AgentPlan, scopeRootRef: String): ExecutionSummary {
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
            ),
        )

        var foldersCreated = 0
        var filesMoved = 0
        var filesRenamed = 0
        var filesTrashed = 0
        var failed = 0

        validated.accepted.forEachIndexed { sequence, operation ->
            val mutationId = mutationRecordDao.insert(
                MutationRecord(
                    taskRunId = taskRunId,
                    sequence = sequence,
                    operationType = operation.toOperationType(),
                    sourceBefore = operation.sourceRef().rawValue(),
                    destinationAfter = operation.destinationRefOrNull()?.rawValue(),
                    sourceFingerprint = null,
                    status = MutationStatus.PENDING,
                    executedAt = null,
                    undoState = null,
                    error = null,
                ),
            )

            when (val result = runOne(operation)) {
                is MutationResult.Success -> {
                    mutationRecordDao.update(
                        MutationRecord(
                            id = mutationId,
                            taskRunId = taskRunId,
                            sequence = sequence,
                            operationType = operation.toOperationType(),
                            sourceBefore = operation.sourceRef().rawValue(),
                            destinationAfter = result.resultRef.rawValue(),
                            sourceFingerprint = null,
                            status = MutationStatus.COMMITTED,
                            executedAt = System.currentTimeMillis(),
                            undoState = null,
                            error = null,
                        ),
                    )
                    reindexAfterMutation(operation, result.resultRef, scopeRootRef)
                    when (operation) {
                        is PlannedOperation.CreateDirectory -> foldersCreated++
                        is PlannedOperation.Move -> filesMoved++
                        is PlannedOperation.Rename -> filesRenamed++
                        is PlannedOperation.Trash -> filesTrashed++
                    }
                }
                is MutationResult.Failure -> {
                    mutationRecordDao.update(
                        MutationRecord(
                            id = mutationId,
                            taskRunId = taskRunId,
                            sequence = sequence,
                            operationType = operation.toOperationType(),
                            sourceBefore = operation.sourceRef().rawValue(),
                            destinationAfter = operation.destinationRefOrNull()?.rawValue(),
                            sourceFingerprint = null,
                            status = MutationStatus.FAILED,
                            executedAt = System.currentTimeMillis(),
                            undoState = null,
                            error = result.reason,
                        ),
                    )
                    failed++
                }
            }
        }

        val summary = ExecutionSummary(taskRunId, foldersCreated, filesMoved, filesRenamed, filesTrashed, failed, validated.rejected)

        taskRunDao.update(
            TaskRun(
                id = taskRunId,
                requestText = plan.goal,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                status = if (failed == 0) TaskRunStatus.COMPLETED else TaskRunStatus.FAILED,
                scanSnapshotId = null,
                planJson = describePlan(plan),
                summary = "${summary.succeededTotal} succeeded ($foldersCreated folders, $filesMoved moved, " +
                    "$filesRenamed renamed, $filesTrashed trashed), $failed failed, ${validated.rejected.size} left untouched",
            ),
        )

        return summary
    }

    private suspend fun runOne(operation: PlannedOperation): MutationResult = try {
        when (operation) {
            is PlannedOperation.CreateDirectory ->
                MutationResult.Success(gateway.createDirectory(operation.parent, operation.name))
            is PlannedOperation.Move -> gateway.move(operation.source, operation.destination)
            is PlannedOperation.Rename -> gateway.rename(operation.source, operation.newName)
            is PlannedOperation.Trash -> gateway.trash(operation.source)
        }
    } catch (t: Throwable) {
        MutationResult.Failure(t.message ?: t.javaClass.simpleName, t)
    }

    /**
     * Keeps the moved/renamed/trashed row itself in sync with reality so a
     * second operation later in the same plan (or the same screen without a
     * rescan) sees the new location. Does *not* walk descendants of a moved
     * directory — those stay stale in the index until the next scan. Not
     * exercised by anything in this milestone (its own plan only ever moves
     * individual files), called out here so it isn't rediscovered the hard
     * way when a future milestone moves a whole subtree.
     */
    private suspend fun reindexAfterMutation(operation: PlannedOperation, newRef: FileRef, scopeRootRef: String) {
        if (operation is PlannedOperation.CreateDirectory) return // Nothing existed to reindex.
        val oldStableRef = operation.sourceRef().rawValue()
        val existing = fileRecordDao.getByStableRef(oldStableRef) ?: return
        fileRecordDao.deleteByStableRef(oldStableRef)

        // A trashed file no longer lives under the scope it was scanned
        // from — Trash isn't itself a scan target yet — so its old record
        // is simply dropped rather than re-inserted under a scopeRootRef
        // that would now be misleading. It reappears if Trash is ever
        // scanned in its own right.
        if (operation is PlannedOperation.Trash) return

        fileRecordDao.upsert(
            existing.copy(
                stableRef = newRef.rawValue(),
                scopeRootRef = scopeRootRef,
                displayName = newRef.displayNameGuess(),
                absolutePathOrUri = newRef.rawValue(),
                lastScannedAt = System.currentTimeMillis(),
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

private fun PlannedOperation.destinationRefOrNull(): FileRef? = when (this) {
    is PlannedOperation.Move -> destination
    else -> null
}

private fun PlannedOperation.toOperationType(): MutationOperationType = when (this) {
    is PlannedOperation.CreateDirectory -> MutationOperationType.CREATE_DIRECTORY
    is PlannedOperation.Move -> MutationOperationType.MOVE
    is PlannedOperation.Rename -> MutationOperationType.RENAME
    is PlannedOperation.Trash -> MutationOperationType.TRASH
}

private fun FileRef.displayNameGuess(): String = rawValue().substringAfterLast('/')
