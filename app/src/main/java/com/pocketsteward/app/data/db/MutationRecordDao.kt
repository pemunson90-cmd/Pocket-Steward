package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface MutationRecordDao {
    @Insert
    suspend fun insert(record: MutationRecord): Long

    @Update
    suspend fun update(record: MutationRecord)

    @Query("SELECT * FROM mutation_records WHERE taskRunId = :taskRunId ORDER BY sequence ASC")
    suspend fun getForTaskRun(taskRunId: Long): List<MutationRecord>

    @Query("SELECT * FROM mutation_records WHERE status = 'PENDING'")
    suspend fun getAllPending(): List<MutationRecord>
}
