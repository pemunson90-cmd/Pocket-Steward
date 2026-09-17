package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.db.TaskRunStatus

/**
 * Plan Section 18: reconcile before resuming anything, on every app start.
 *
 * A [com.pocketsteward.app.data.db.TaskRun] stuck in `RUNNING` means the
 * process died somewhere inside `PlanExecutor.execute()` — after at least
 * one `MutationRecord` was journaled `PENDING`, before that run ever reached
 * its own final `taskRunDao.update()`. A `PENDING` record left over from
 * that crash has a genuinely unknown outcome: the gateway call it was
 * journaling for might have completed for real an instant before the
 * crash, or might never have run at all. Guessing either way risks the two
 * things this app promises never to do — silently losing a mutation, or
 * silently repeating one — so this guesses neither. Every `PENDING` record
 * from a `RUNNING` run is marked `FAILED` with an explanation, and the
 * `TaskRun` itself is marked `FAILED`, never `COMPLETED`. Nothing here
 * re-executes or auto-reverses anything; that's a deliberately narrower
 * promise than a cleverer reconciler could make, chosen because a wrong
 * guess here is worse than an honest "check this by hand."
 *
 * Any `COMMITTED` mutations from that same run are untouched — they're not
 * in question, and they're still available to [UndoExecutor] if the user
 * wants to reverse whatever did get through before the crash.
 */
class ReconciliationService(
    private val taskRunDao: TaskRunDao,
    private val mutationRecordDao: MutationRecordDao,
) {
    suspend fun reconcileInterruptedRuns() {
        val stuckRuns = taskRunDao.getAllWithStatus(TaskRunStatus.RUNNING)

        for (taskRun in stuckRuns) {
            val pending = mutationRecordDao.getForTaskRun(taskRun.id)
                .filter { it.status == MutationStatus.PENDING }

            for (mutation in pending) {
                mutationRecordDao.update(
                    mutation.copy(
                        status = MutationStatus.FAILED,
                        error = "Outcome unknown — the app stopped before this operation could be " +
                            "confirmed. Check the file by hand before retrying.",
                    ),
                )
            }

            taskRunDao.update(
                taskRun.copy(
                    status = TaskRunStatus.FAILED,
                    completedAt = taskRun.completedAt ?: System.currentTimeMillis(),
                    summary = "Interrupted before completion (${pending.size} operation(s) with an " +
                        "unknown outcome) — not auto-recovered, check manually.",
                ),
            )
        }
    }
}
