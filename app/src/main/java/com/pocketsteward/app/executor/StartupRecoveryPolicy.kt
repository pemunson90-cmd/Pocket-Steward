package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.DurablePlanCodec

/**
 * Decides whether a task should automatically resume after process restart.
 *
 * Only a task that was still RUNNING and carries the exact durable approved
 * plan may restart without another tap. CANCELLED means the user explicitly
 * paused it and is therefore never auto-resumed.
 */
object StartupRecoveryPolicy {
    fun taskToAutoResume(tasks: List<TaskRun>): TaskRun? =
        tasks
            .asSequence()
            .filter { it.status == TaskRunStatus.RUNNING }
            .filter { DurablePlanCodec.isDurable(it.planJson) }
            .minByOrNull { it.startedAt }
}
