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

    @Query("SELECT stableRef FROM indexed_documents WHERE sourceRoot = :sourceRoot")
    suspend fun getStableRefsForRoot(sourceRoot: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDocument(document: IndexedDocument)

    @Query("DELETE FROM indexed_segments_fts WHERE rowid IN (SELECT id FROM indexed_segments WHERE stableRef = :stableRef)")
    suspend fun deleteFtsForDocument(stableRef: String)

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
}
