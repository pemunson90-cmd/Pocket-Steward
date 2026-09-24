package com.pocketsteward.app.content.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ContentIndexDao {
    @Query("SELECT * FROM indexed_documents WHERE stableRef = :stableRef")
    suspend fun getDocument(stableRef: String): IndexedDocument?

    @Query("SELECT * FROM indexed_documents WHERE sourceRoot IN (:sourceRoots) AND extractionStatus = 'INDEXED'")
    suspend fun getIndexedDocumentsForRoots(sourceRoots: List<String>): List<IndexedDocument>

    @Query("SELECT stableRef FROM indexed_documents WHERE sourceRoot = :sourceRoot")
    suspend fun getStableRefsForRoot(sourceRoot: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDocument(document: IndexedDocument)

    @Query("DELETE FROM indexed_segments_fts WHERE rowid IN (SELECT id FROM indexed_segments WHERE stableRef = :stableRef)")
    suspend fun deleteFtsForDocument(stableRef: String)

    @Query("SELECT * FROM indexed_segments WHERE stableRef = :stableRef ORDER BY ordinal ASC")
    suspend fun getSegmentsForDocument(stableRef: String): List<IndexedSegment>

    @Query("DELETE FROM indexed_segments WHERE stableRef = :stableRef")
    suspend fun deleteSegmentsForDocument(stableRef: String)

    @Query("DELETE FROM indexed_documents WHERE stableRef = :stableRef")
    suspend fun deleteDocument(stableRef: String)

    @Insert
    suspend fun insertSegment(segment: IndexedSegment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(row: IndexedSegmentFts)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: ContentIndexState)

    @Query("SELECT * FROM content_index_state WHERE sourceRoot = :sourceRoot")
    suspend fun getState(sourceRoot: String): ContentIndexState?

    @Query("SELECT COUNT(*) FROM indexed_documents")
    suspend fun countDocuments(): Int

    @Query("SELECT COUNT(*) FROM indexed_segments")
    suspend fun countSegments(): Int

    @Query("SELECT COUNT(DISTINCT sourceRoot) FROM indexed_documents")
    suspend fun countRoots(): Int



    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putJob(job: ContentIndexJob)

    @Query("SELECT * FROM content_index_jobs WHERE sourceRoot = :sourceRoot")
    suspend fun getJob(sourceRoot: String): ContentIndexJob?

    @Query("SELECT * FROM content_index_jobs WHERE sourceRoot IN (:sourceRoots)")
    suspend fun getJobs(sourceRoots: List<String>): List<ContentIndexJob>

    @Query("SELECT * FROM content_index_jobs WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED') ORDER BY updatedAt ASC")
    suspend fun getResumableJobs(): List<ContentIndexJob>

    @Query("DELETE FROM content_index_jobs WHERE sourceRoot = :sourceRoot")
    suspend fun deleteJob(sourceRoot: String)

    @Query("DELETE FROM content_index_jobs")
    suspend fun clearJobs()

    @Query("DELETE FROM content_index_state WHERE sourceRoot = :sourceRoot")
    suspend fun clearState(sourceRoot: String)

    @Query("DELETE FROM indexed_segments_fts")
    suspend fun clearFts()

    @Query("DELETE FROM indexed_segments")
    suspend fun clearSegments()

    @Query("DELETE FROM indexed_documents")
    suspend fun clearDocuments()

    @Query("DELETE FROM content_index_state")
    suspend fun clearStates()

    @Transaction
    suspend fun replaceSegments(stableRef: String, segments: List<IndexedSegment>) {
        deleteFtsForDocument(stableRef)
        deleteSegmentsForDocument(stableRef)
        for (segment in segments) {
            val id = insertSegment(segment)
            require(id <= Int.MAX_VALUE) { "Content index row id exceeded FTS4 rowid range." }
            insertFts(
                IndexedSegmentFts(
                    rowid = id.toInt(),
                    body = segment.body,
                ),
            )
        }
    }

    @Transaction
    suspend fun replaceDocument(document: IndexedDocument, segments: List<IndexedSegment>) {
        putDocument(document)
        replaceSegments(document.stableRef, segments)
    }

    @Transaction
    suspend fun removeDocument(stableRef: String) {
        deleteFtsForDocument(stableRef)
        deleteSegmentsForDocument(stableRef)
        deleteDocument(stableRef)
    }

    @Transaction
    suspend fun clearAll() {
        clearFts()
        clearSegments()
        clearDocuments()
        clearStates()
        clearJobs()
    }

    @Query(
        """
        SELECT s.id AS segmentId,
               s.stableRef AS stableRef,
               d.sourceRoot AS sourceRoot,
               d.displayName AS displayName,
               d.parentRef AS parentRef,
               d.extension AS extension,
               d.category AS category,
               d.sizeBytes AS sizeBytes,
               d.modifiedAt AS modifiedAt,
               d.contentKind AS contentKind,
               s.pageNumber AS pageNumber,
               s.ocr AS ocr,
               snippet(indexed_segments_fts, '⟦', '⟧', ' … ', 0, 28) AS snippet
        FROM indexed_segments_fts
        INNER JOIN indexed_segments s ON s.id = indexed_segments_fts.rowid
        INNER JOIN indexed_documents d ON d.stableRef = s.stableRef
        WHERE indexed_segments_fts MATCH :matchQuery
          AND d.sourceRoot IN (:sourceRoots)
        LIMIT :limit
        """,
    )
    suspend fun searchRows(
        matchQuery: String,
        sourceRoots: List<String>,
        limit: Int,
    ): List<IndexedSearchRow>

    /**
     * Ask-your-files retrieval: whole segment bodies (not snippets) for rows
     * matching any keyword, across every indexed folder. Ranking happens in
     * [com.pocketsteward.app.content.ask.AskRetrieval], which is unit tested;
     * this only fetches candidates.
     */
    @Query(
        """
        SELECT s.id AS segmentId,
               s.stableRef AS stableRef,
               d.displayName AS displayName,
               d.parentRef AS parentRef,
               d.extension AS extension,
               s.pageNumber AS pageNumber,
               s.ocr AS ocr,
               s.body AS body
        FROM indexed_segments_fts
        INNER JOIN indexed_segments s ON s.id = indexed_segments_fts.rowid
        INNER JOIN indexed_documents d ON d.stableRef = s.stableRef
        WHERE indexed_segments_fts MATCH :matchQuery
        LIMIT :limit
        """,
    )
    suspend fun askCandidateRows(
        matchQuery: String,
        limit: Int,
    ): List<com.pocketsteward.app.content.ask.AskCandidateRow>
}
