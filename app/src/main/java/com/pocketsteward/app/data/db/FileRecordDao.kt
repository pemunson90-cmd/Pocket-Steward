package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FileRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<FileRecord>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: FileRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScopeTag(scope: FileScope)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScopeTags(scopes: List<FileScope>)

    @Query("SELECT * FROM file_records WHERE id = :id")
    suspend fun getById(id: Long): FileRecord?

    @Query("SELECT * FROM file_records WHERE parentRef = :parentRef ORDER BY isDirectory DESC, displayName ASC")
    fun observeChildren(parentRef: String): Flow<List<FileRecord>>

    @Query("SELECT COUNT(*) FROM file_records WHERE parentRef = :parentRef")
    suspend fun countChildren(parentRef: String): Int

    @Query("SELECT file_records.* FROM file_records INNER JOIN file_scopes ON file_records.stableRef = file_scopes.fileRef WHERE file_scopes.scopeRoot = :scopeRootRef AND isDirectory = 0")
    suspend fun getFilesUnderScopeRoot(scopeRootRef: String): List<FileRecord>

    @Query("SELECT file_records.* FROM file_records INNER JOIN file_scopes ON file_records.stableRef = file_scopes.fileRef WHERE file_scopes.scopeRoot = :scopeRootRef AND isDirectory = 0 ORDER BY sizeBytes DESC LIMIT :limit")
    suspend fun getLargestFiles(scopeRootRef: String, limit: Int): List<FileRecord>

    @Query("SELECT file_records.* FROM file_records INNER JOIN file_scopes ON file_records.stableRef = file_scopes.fileRef WHERE file_scopes.scopeRoot = :scopeRootRef AND isDirectory = 0 AND modifiedAt IS NOT NULL AND modifiedAt < :cutoffMillis ORDER BY modifiedAt ASC")
    suspend fun getFilesOlderThan(scopeRootRef: String, cutoffMillis: Long): List<FileRecord>

    @Query("SELECT file_records.* FROM file_records INNER JOIN file_scopes ON file_records.stableRef = file_scopes.fileRef WHERE file_scopes.scopeRoot = :scopeRootRef")
    suspend fun getAllUnderScopeRoot(scopeRootRef: String): List<FileRecord>

    @Query("SELECT * FROM file_records WHERE stableRef = :stableRef")
    suspend fun getByStableRef(stableRef: String): FileRecord?

    @Query("DELETE FROM file_records WHERE stableRef = :stableRef")
    suspend fun deleteByStableRef(stableRef: String)

    @Query("DELETE FROM file_scopes WHERE scopeRoot = :scopeRootRef")
    suspend fun removeScopeTags(scopeRootRef: String)

    @Query("DELETE FROM file_records WHERE stableRef NOT IN (SELECT fileRef FROM file_scopes)")
    suspend fun deleteOrphanedFiles()

    @Transaction
    suspend fun clearScopeRoot(scopeRootRef: String) {
        removeScopeTags(scopeRootRef)
        deleteOrphanedFiles()
    }

    @Query("DELETE FROM file_records")
    suspend fun clearAll()
}
