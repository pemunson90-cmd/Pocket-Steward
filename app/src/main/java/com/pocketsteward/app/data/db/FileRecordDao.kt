package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<FileRecord>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: FileRecord): Long

    @Query("SELECT * FROM file_records WHERE id = :id")
    suspend fun getById(id: Long): FileRecord?

    @Query("SELECT * FROM file_records WHERE parentRef = :parentRef ORDER BY isDirectory DESC, displayName ASC")
    fun observeChildren(parentRef: String): Flow<List<FileRecord>>

    @Query("SELECT COUNT(*) FROM file_records WHERE parentRef = :parentRef")
    suspend fun countChildren(parentRef: String): Int

    @Query("DELETE FROM file_records WHERE stableRef = :stableRef")
    suspend fun deleteByStableRef(stableRef: String)

    @Query("DELETE FROM file_records")
    suspend fun clearAll()
}
