package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskRunDao {
    @Insert
    suspend fun insert(taskRun: TaskRun): Long

    @Transaction
    suspend fun insertWhenIdle(taskRun: TaskRun): Long {
        require(getRunsNeedingRecovery().none { it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.UNDOING }) {
            "Another file task is active. Pause or finish it before starting another."
        }
        return insert(taskRun)
    }

    @Transaction
    suspend fun activateWhenIdle(taskRun: TaskRun) {
        require(getRunsNeedingRecovery().none { it.id != taskRun.id && (it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.UNDOING) }) {
            "Another file task is active. Pause or finish it before resuming or undoing."
        }
        update(taskRun)
    }

    @Update
    suspend fun update(taskRun: TaskRun)

    @Query("SELECT * FROM task_runs WHERE id = :id")
    suspend fun getById(id: Long): TaskRun?

    @Query("SELECT COALESCE(MAX(id), 0) FROM task_runs")
    suspend fun latestTaskId(): Long

    @Query("SELECT id, planJson FROM task_runs WHERE id > :afterId ORDER BY id ASC")
    suspend fun plansAfter(afterId: Long): List<QueuedPlanIdentity>

    @Query("SELECT substr(requestText, 1, 20000) AS request, status, startedAt, completedAt, substr(summary, 1, 20000) AS summary FROM task_runs ORDER BY startedAt DESC LIMIT 1000")
    suspend fun portableSummaries(): List<com.pocketsteward.app.backup.ArchivedTaskSummary>

    @Query("SELECT * FROM task_runs ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TaskRun>>


    @Query("SELECT * FROM task_runs WHERE status = 'RUNNING' ORDER BY startedAt ASC")
    suspend fun getRunning(): List<TaskRun>

    @Query(
        "UPDATE task_runs SET status = 'CANCELLED', completedAt = :completedAt, summary = :summary " +
            "WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun markRunningPaused(
        id: Long,
        completedAt: Long,
        summary: String,
    ): Int

    @Query("SELECT * FROM task_runs WHERE status IN ('RUNNING', 'NEEDS_REVIEW', 'UNDOING', 'UNDO_PARTIAL') ORDER BY startedAt ASC")
    suspend fun getRunsNeedingRecovery(): List<TaskRun>
}

data class QueuedPlanIdentity(val id: Long, val planJson: String)
