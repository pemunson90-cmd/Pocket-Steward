package com.pocketsteward.app.content.index

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class IndexedExtractionStatus {
    INDEXED,
    UNSUPPORTED,
    FAILED,
}

@Entity(
    tableName = "indexed_documents",
    indices = [
        Index("sourceRoot"),
        Index("parentRef"),
        Index("extension"),
        Index("modifiedAt"),
    ],
)
data class IndexedDocument(
    @PrimaryKey val stableRef: String,
    val sourceRoot: String,
    val displayName: String,
    val parentRef: String?,
    val extension: String,
    val category: String,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val quickFingerprint: String?,
    val contentKind: String?,
    val extractionStatus: String,
    val extractionError: String?,
    val extractorVersion: Int,
    val indexedAt: Long,
    val segmentCount: Int,
)

@Entity(
    tableName = "indexed_segments",
    indices = [Index("stableRef"), Index("pageNumber"), Index("ocr")],
)
data class IndexedSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableRef: String,
    val ordinal: Int,
    val pageNumber: Int?,
    val ocr: Boolean,
    val body: String,
)

@Fts4
@Entity(tableName = "indexed_segments_fts")
data class IndexedSegmentFts(
    @PrimaryKey
    val rowid: Int,
    val stableRef: String,
    val body: String,
)

@Entity(tableName = "content_index_state")
data class ContentIndexState(
    @PrimaryKey val sourceRoot: String,
    val eligibleCount: Int,
    val processedCount: Int,
    val completed: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
    val extractorVersion: Int,
)

data class IndexedSearchHit(
    val segmentId: Long,
    val stableRef: String,
    val pageNumber: Int?,
    val ocr: Boolean,
    val snippet: String,
)
