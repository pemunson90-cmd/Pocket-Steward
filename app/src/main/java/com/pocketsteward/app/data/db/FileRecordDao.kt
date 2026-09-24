package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FileRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: FileRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllRecords(records: List<FileRecord>): List<Long>

    @Update
    suspend fun update(record: FileRecord): Int

    @Update
    suspend fun updateAllRecords(records: List<FileRecord>): Int

    @Transaction
    suspend fun upsert(record: FileRecord): Long {
        val existing = getByStableRef(record.stableRef)
        return if (existing == null) {
            insert(record)
        } else {
            update(record.copy(id = existing.id))
            existing.id
        }
    }

    @Transaction
    suspend fun upsertAll(records: List<FileRecord>): List<Long> =
        records.map { upsert(it) }

    @Transaction
    suspend fun upsertFromScan(record: FileRecord): Long {
        val existing = getByStableRef(record.stableRef)
        return if (existing == null) {
            insert(record)
        } else {
            update(mergeScanRecord(existing, record))
            existing.id
        }
    }

    /**
     * Batch Level-0 scan merge.
     *
     * The old implementation performed a SELECT + UPDATE/INSERT per file.
     * On a 20k-file phone that made Room chatter thousands of times even
     * though the scanner already hands us one complete directory batch.
     * Resolve existing rows once, merge in memory, then issue one bulk update
     * and one bulk insert inside this transaction.
     */
    @Transaction
    suspend fun upsertAllFromScan(records: List<FileRecord>): List<Long> {
        if (records.isEmpty()) return emptyList()

        // Keep each IN() below conservative SQLite bind limits. A flat
        // Downloads directory can easily contain thousands of files.
        val existingByRef = records
            .map { it.stableRef }
            .chunked(SCAN_LOOKUP_CHUNK)
            .flatMap { refs -> getByStableRefs(refs) }
            .associateBy { it.stableRef }

        val updates = mutableListOf<FileRecord>()
        val inserts = mutableListOf<FileRecord>()
        records.forEach { scanned ->
            val existing = existingByRef[scanned.stableRef]
            if (existing == null) {
                inserts += scanned
            } else {
                updates += mergeScanRecord(existing, scanned)
            }
        }

        if (updates.isNotEmpty()) updateAllRecords(updates)

        val insertedIds = if (inserts.isEmpty()) {
            emptyList()
        } else {
            insertAllRecords(inserts)
        }
        val insertedByRef = inserts.indices.associate { index ->
            inserts[index].stableRef to insertedIds[index]
        }

        return records.map { record ->
            existingByRef[record.stableRef]?.id
                ?: insertedByRef[record.stableRef]
                ?: error("Scan upsert lost row id for " + record.stableRef)
        }
    }

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

    @Query("SELECT * FROM file_records WHERE stableRef IN (:stableRefs)")
    suspend fun getByStableRefs(stableRefs: List<String>): List<FileRecord>


    @Query("UPDATE file_records SET quickFingerprint = :value WHERE stableRef = :stableRef")
    suspend fun updateQuickFingerprint(stableRef: String, value: String)

    @Query("UPDATE file_records SET sha256 = :value WHERE stableRef = :stableRef")
    suspend fun updateSha256(stableRef: String, value: String)

    @Query("DELETE FROM file_records WHERE stableRef = :stableRef")
    suspend fun deleteByStableRef(stableRef: String)

    @Query("SELECT DISTINCT scopeRoot FROM file_scopes")
    suspend fun getKnownScopeRoots(): List<String>

    @Query("DELETE FROM file_scopes WHERE scopeRoot = :scopeRootRef")
    suspend fun removeScopeTags(scopeRootRef: String)

    @Query(
        "DELETE FROM file_scopes WHERE scopeRoot = :scopeRootRef " +
            "AND fileRef IN (SELECT stableRef FROM file_records WHERE lastScannedAt < :scanStartedAt)",
    )
    suspend fun removeStaleScopeTags(scopeRootRef: String, scanStartedAt: Long)

    @Query("DELETE FROM file_records WHERE stableRef NOT IN (SELECT fileRef FROM file_scopes)")
    suspend fun deleteOrphanedFiles()

    @Transaction
    suspend fun clearScopeRoot(scopeRootRef: String) {
        removeScopeTags(scopeRootRef)
        deleteOrphanedFiles()
    }

    @Query("DELETE FROM file_records")
    suspend fun clearAll()
    companion object {
        const val SCAN_LOOKUP_CHUNK = 500
    }

    // ---- Background library ------------------------------------------------

    @Query("SELECT COUNT(*) FROM file_scopes INNER JOIN file_records ON file_records.stableRef = file_scopes.fileRef WHERE file_scopes.scopeRoot = :scopeRootRef AND file_records.isDirectory = 0")
    suspend fun countFilesInScope(scopeRootRef: String): Int

    @Query("DELETE FROM file_scopes WHERE scopeRoot = :scopeRootRef")
    suspend fun removeAllScopeTags(scopeRootRef: String)

    @Query("SELECT COUNT(*) FROM file_scopes WHERE fileRef = :fileRef AND scopeRoot = :scopeRootRef")
    suspend fun scopeTagCount(fileRef: String, scopeRootRef: String): Int

    /**
     * Tags everything the library holds at or under [folderRef] as belonging
     * to [newScope]. Prefix matched with substr rather than LIKE, so the
     * underscores and percent signs that appear in real file names are
     * literal. [folderSlash] is [folderRef] with one trailing slash.
     */
    @Query(
        "INSERT OR IGNORE INTO file_scopes (fileRef, scopeRoot) " +
            "SELECT fileRef, :newScope FROM file_scopes " +
            "WHERE scopeRoot = :libraryScope " +
            "AND (fileRef = :folderRef OR substr(fileRef, 1, length(:folderSlash)) = :folderSlash)",
    )
    suspend fun copyScopeTagsUnder(libraryScope: String, newScope: String, folderRef: String, folderSlash: String)

    /**
     * Makes [newScope] an exact slice of the library under [folderRef],
     * replacing whatever that scope held before. Records are shared, so this
     * only moves tags; nothing on disk is involved.
     */
    @Transaction
    suspend fun adoptLibrarySlice(libraryScope: String, newScope: String, folderRef: String) {
        val folder = folderRef.trimEnd('/')
        removeAllScopeTags(newScope)
        copyScopeTagsUnder(libraryScope, newScope, folder, "$folder/")
        deleteOrphanedFiles()
    }

    // ---- Files tab: categories, recent, name search over the library ------

    @Query(
        "SELECT r.* FROM file_records r INNER JOIN file_scopes s ON r.stableRef = s.fileRef " +
            "WHERE s.scopeRoot = :root AND r.isDirectory = 0 AND lower(r.extension) IN (:extensions) " +
            "ORDER BY r.modifiedAt DESC LIMIT :limit",
    )
    suspend fun libraryFilesByExtension(root: String, extensions: List<String>, limit: Int): List<FileRecord>

    @Query(
        "SELECT r.* FROM file_records r INNER JOIN file_scopes s ON r.stableRef = s.fileRef " +
            "WHERE s.scopeRoot = :root AND r.isDirectory = 0 AND r.modifiedAt IS NOT NULL " +
            "ORDER BY r.modifiedAt DESC LIMIT :limit",
    )
    suspend fun libraryRecentFiles(root: String, limit: Int): List<FileRecord>

    /** [pattern] is a LIKE pattern with \ as the escape character. */
    @Query(
        "SELECT r.* FROM file_records r INNER JOIN file_scopes s ON r.stableRef = s.fileRef " +
            "WHERE s.scopeRoot = :root AND r.displayName LIKE :pattern ESCAPE '\\' " +
            "ORDER BY r.isDirectory DESC, length(r.displayName) ASC, r.modifiedAt DESC LIMIT :limit",
    )
    suspend fun librarySearchByName(root: String, pattern: String, limit: Int): List<FileRecord>

    @Query(
        "SELECT lower(r.extension) AS extension, COUNT(*) AS fileCount, COALESCE(SUM(r.sizeBytes), 0) AS totalBytes " +
            "FROM file_records r INNER JOIN file_scopes s ON r.stableRef = s.fileRef " +
            "WHERE s.scopeRoot = :root AND r.isDirectory = 0 GROUP BY lower(r.extension)",
    )
    suspend fun libraryExtensionStats(root: String): List<ExtensionStat>
}

/** Per-extension totals for the Files tab's category tiles. */
data class ExtensionStat(val extension: String, val fileCount: Int, val totalBytes: Long)
