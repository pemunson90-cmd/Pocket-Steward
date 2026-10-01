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
