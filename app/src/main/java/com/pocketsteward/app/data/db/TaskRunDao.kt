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

    @Query("SELECT * FROM task_runs WHERE status IN ('RUNNING', 'NEEDS_REVIEW', 'UNDOING', 'UNDO_PARTIAL') ORDER BY startedAt ASC")
    suspend fun getRunsNeedingRecovery(): List<TaskRun>
}
