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

    /** Used at startup to find task runs a crash interrupted mid-execution — see ReconciliationService. */
    @Query("SELECT * FROM task_runs WHERE status = :status")
    suspend fun getAllWithStatus(status: TaskRunStatus): List<TaskRun>
}
