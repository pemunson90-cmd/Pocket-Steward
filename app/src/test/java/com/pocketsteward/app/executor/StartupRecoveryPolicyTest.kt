package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import org.junit.Test

class StartupRecoveryPolicyTest {
    @Test
    fun runningDurableTaskAutoResumes() {
        val task = task(7, TaskRunStatus.RUNNING, durable = true, startedAt = 10)

        assertThat(StartupRecoveryPolicy.taskToAutoResume(listOf(task))).isEqualTo(task)
    }

    @Test
    fun explicitlyPausedTaskDoesNotAutoResume() {
        val paused = task(7, TaskRunStatus.CANCELLED, durable = true, startedAt = 10)

        assertThat(StartupRecoveryPolicy.taskToAutoResume(listOf(paused))).isNull()
    }

    @Test
    fun legacyRunningTaskWithoutDurablePlanDoesNotAutoResume() {
        val legacy = task(7, TaskRunStatus.RUNNING, durable = false, startedAt = 10)

        assertThat(StartupRecoveryPolicy.taskToAutoResume(listOf(legacy))).isNull()
    }

    @Test
    fun oldestRunningDurableTaskWinsIfCorruptHistoryContainsMoreThanOne() {
        val later = task(8, TaskRunStatus.RUNNING, durable = true, startedAt = 20)
        val earlier = task(7, TaskRunStatus.RUNNING, durable = true, startedAt = 10)

        assertThat(StartupRecoveryPolicy.taskToAutoResume(listOf(later, earlier))).isEqualTo(earlier)
    }

    private fun task(
        id: Long,
        status: TaskRunStatus,
        durable: Boolean,
        startedAt: Long,
    ): TaskRun {
        val plan = if (durable) {
            DurablePlanCodec.encode(
                "move",
                listOf(
                    PlannedOperation.Move(
                        FileRef.Direct("/Download/a.txt"),
                        FileRef.Direct("/Documents/a.txt"),
                        "move",
                    ),
                ),
            )
        } else {
            "legacy plan"
        }
        return TaskRun(
            id = id,
            requestText = "move",
            startedAt = startedAt,
            completedAt = null,
            status = status,
            scanSnapshotId = null,
            planJson = plan,
            summary = null,
            scopeRootRef = "/Download",
            storageAccessMode = StorageAccessMode.DIRECT,
            undoCompletedAt = null,
        )
    }
}
