package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class TaskJournalProgress(
    val taskRunId: Long,
    val journaledCount: Long,
    val pendingCount: Long,
    val failedCount: Long,
)

@Dao
interface MutationRecordDao {
    @Insert
    suspend fun insert(record: MutationRecord): Long

    @Update
    suspend fun update(record: MutationRecord)

    @Query("SELECT * FROM mutation_records WHERE id = :id")
    suspend fun getById(id: Long): MutationRecord?

    @Query("SELECT * FROM mutation_records WHERE taskRunId = :taskRunId ORDER BY sequence ASC")
    suspend fun getForTaskRun(taskRunId: Long): List<MutationRecord>

    @Query("SELECT * FROM mutation_records WHERE taskRunId = :taskRunId ORDER BY sequence DESC")
    suspend fun getForTaskRunReverse(taskRunId: Long): List<MutationRecord>

    @Query(
        "SELECT taskRunId AS taskRunId, " +
            "COUNT(*) AS journaledCount, " +
            "SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pendingCount, " +
            "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount " +
            "FROM mutation_records GROUP BY taskRunId",
    )
    fun observeTaskProgress(): Flow<List<TaskJournalProgress>>

    @Query("SELECT * FROM mutation_records WHERE status = 'PENDING'")
    suspend fun getAllPending(): List<MutationRecord>

    @Query("SELECT * FROM mutation_records WHERE undoState = 'PENDING'")
    suspend fun getAllPendingUndo(): List<MutationRecord>

    /**
     * Everything currently sitting in Trash, newest first: committed TRASH
     * mutations that haven't been reversed. The journal is the source of truth
     * here rather than listing the Trash folder, because it also knows *when*
     * each file was trashed and *which task* did it, and restoring a file is
     * just that mutation's own inverse.
     */
    @Query(
        "SELECT * FROM mutation_records WHERE operationType = 'TRASH' " +
            "AND status = 'COMMITTED' AND undoState = 'AVAILABLE' ORDER BY executedAt DESC",
    )
    fun observeTrashed(): Flow<List<MutationRecord>>
}
