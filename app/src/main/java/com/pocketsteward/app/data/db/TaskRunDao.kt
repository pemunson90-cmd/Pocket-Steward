package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskRunDao {
    @Insert
    suspend fun insert(taskRun: TaskRun): Long

    @Update
    suspend fun update(taskRun: TaskRun)

    @Query("SELECT * FROM task_runs WHERE id = :id")
    suspend fun getById(id: Long): TaskRun?

    @Query("SELECT * FROM task_runs ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TaskRun>>

    @Query("SELECT COUNT(*) FROM task_runs")
    suspend fun countAllRuns(): Int

    @Query("SELECT * FROM task_runs WHERE completedAt IS NOT NULL ORDER BY completedAt DESC LIMIT 1")
    suspend fun getMostRecentCompleted(): TaskRun?

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
