package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.MutationRecord
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.InverseAction
import com.pocketsteward.app.plan.InverseCalculator
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue

data class RejectedUndo(val mutation: MutationRecord, val reason: String)

data class UndoSummary(
    val taskRunId: Long,
    val undone: Int,
    val blockedAt: RejectedUndo?,
)

/**
 * Plan Section 15: reverses a [com.pocketsteward.app.data.db.TaskRun]'s
 * committed mutations in strict reverse order, stopping — not overwriting —
 * the moment one can't be reversed cleanly. Each
 * [com.pocketsteward.app.data.db.MutationRecord] already carries everything
 * [InverseCalculator] needs; this class just walks the journal backward and
 * applies what it computes.
 *
 * Stopping on the first failure, rather than skipping it and continuing, is
 * deliberate: an earlier (in forward order) operation's safety can depend on
 * a later one having actually been undone first — e.g. two files that
 * shared a destination name across the run. Partial undo is a real,
 * expected outcome here, not a bug; the caller sees exactly how far it got
 * and why it stopped, same as the executor's own `leftUntouched`.
 */
class UndoExecutor(
    private val gateway: StorageGateway,
    private val fileRecordDao: FileRecordDao,
    private val taskRunDao: TaskRunDao,
    private val mutationRecordDao: MutationRecordDao,
) {
    suspend fun undo(taskRunId: Long): UndoSummary {
        val taskRun = taskRunDao.getById(taskRunId) ?: error("Task run not found: $taskRunId")
        check(taskRun.status != TaskRunStatus.RUNNING) {
            "Task run is still in progress, can't undo yet."
        }

        val mutations = mutationRecordDao.getForTaskRun(taskRunId)
            .filter { it.status == MutationStatus.COMMITTED }
            .sortedByDescending { it.sequence }

        var undone = 0
        var blocked: RejectedUndo? = null

        for (mutation in mutations) {
            val action = InverseCalculator.compute(
                operationType = mutation.operationType,
                sourceBefore = mutation.sourceBefore,
                destinationAfter = mutation.destinationAfter,
                undoState = mutation.undoState,
            )

            when (val result = applyInverse(action)) {
                null -> {
                    // NothingToUndo (e.g. a CreateDirectory that only ever
                    // found an existing folder) — correctly handled, not
                    // blocked.
                    mutationRecordDao.update(mutation.copy(status = MutationStatus.UNDONE))
                    undone++
                }
                is MutationResult.Success -> {
                    mutationRecordDao.update(mutation.copy(status = MutationStatus.UNDONE))
                    reindexAfterUndo(action)
                    undone++
                }
                is MutationResult.Failure -> {
                    blocked = RejectedUndo(mutation, result.reason)
                }
            }

            if (blocked != null) break
        }

        taskRunDao.update(
            taskRun.copy(
                status = if (blocked == null) TaskRunStatus.UNDONE else TaskRunStatus.FAILED,
                summary = if (blocked == null) {
                    "$undone operation(s) undone"
                } else {
                    "$undone operation(s) undone, stopped: ${blocked.reason}"
                },
            ),
        )

        return UndoSummary(taskRunId, undone, blocked)
    }

    private suspend fun applyInverse(action: InverseAction): MutationResult? = when (action) {
        is InverseAction.MoveBack -> gateway.move(action.from, action.to)
        is InverseAction.RemoveDirectoryIfEmpty -> gateway.removeIfEmpty(action.directory)
        InverseAction.NothingToUndo -> null
    }

    /**
     * Best-effort index touch-up so the app doesn't look stale immediately
     * after Undo finishes — a real scan is always the ground truth, this
     * just avoids the obviously-wrong-looking gap in between. Silently
     * no-ops if the row isn't found (e.g. a trashed file's record was
     * dropped, not re-tagged, when it was trashed — see
     * PlanExecutor.reindexAfterMutation).
     */
    private suspend fun reindexAfterUndo(action: InverseAction) {
        when (action) {
            is InverseAction.MoveBack -> {
                val currentStableRef = action.from.rawValue()
                val existing = fileRecordDao.getByStableRef(currentStableRef) ?: return
                fileRecordDao.deleteByStableRef(currentStableRef)
                fileRecordDao.upsert(
                    existing.copy(
                        stableRef = action.to.rawValue(),
                        absolutePathOrUri = action.to.rawValue(),
                        displayName = action.to.rawValue().substringAfterLast('/'),
                        lastScannedAt = System.currentTimeMillis(),
                    ),
                )
            }
            is InverseAction.RemoveDirectoryIfEmpty -> {
                fileRecordDao.deleteByStableRef(action.directory.rawValue())
            }
            InverseAction.NothingToUndo -> Unit
        }
    }
}
