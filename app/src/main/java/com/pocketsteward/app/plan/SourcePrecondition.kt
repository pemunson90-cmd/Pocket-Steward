package com.pocketsteward.app.plan

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileMetadata

/**
 * Cheap approval-time identity check for a source file.
 *
 * This is intentionally metadata, not a whole-file hash. Hashing thousands of
 * files merely because a plan was approved would turn a safe preview into a
 * battery furnace. Exact duplicate-trash operations still carry their SHA-256
 * separately; this precondition closes the ordinary "file changed after
 * preview/approval" race for move/copy/rename without making every task hash
 * the phone.
 */
data class SourcePrecondition(
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long?,
)

object SourcePreconditions {
    fun from(metadata: FileMetadata): SourcePrecondition? =
        if (metadata.isDirectory) null else SourcePrecondition(
            sizeBytes = metadata.sizeBytes,
            modifiedAtEpochMs = metadata.modifiedAtEpochMs,
        )

    fun from(record: FileRecord): SourcePrecondition? =
        if (record.isDirectory) null else SourcePrecondition(
            sizeBytes = record.sizeBytes,
            modifiedAtEpochMs = record.modifiedAt,
        )

    fun matches(expected: SourcePrecondition, current: SourcePrecondition): Boolean {
        if (expected.sizeBytes != current.sizeBytes) return false
        val expectedModified = expected.modifiedAtEpochMs
        return expectedModified == null || expectedModified == current.modifiedAtEpochMs
    }
}
