package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MutationOperationType { CREATE_DIRECTORY, MOVE, RENAME, COPY, TRASH, WRITE_TEXT_FILE }
enum class MutationStatus { PENDING, COMMITTED, FAILED, NEEDS_REVIEW, UNDONE }
enum class UndoState { NOT_AVAILABLE, AVAILABLE, PENDING, UNDONE, BLOCKED, FAILED }

/**
 * Durable write-ahead record for one filesystem mutation and its inverse.
 *
 * sourceBefore / destinationAfter are type-preserving FileRefJournalCodec
 * strings, not raw paths. A PENDING row is inserted before touching the
 * filesystem. That makes process death between the filesystem call and the
 * COMMITTED update recoverable instead of invisible.
 */
@Entity(
    tableName = "mutation_records",
    foreignKeys = [
        ForeignKey(
            entity = TaskRun::class,
            parentColumns = ["id"],
            childColumns = ["taskRunId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("taskRunId")],
)
data class MutationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskRunId: Long,
    val sequence: Int,
    val operationType: MutationOperationType,
    val sourceBefore: String,
    val destinationAfter: String?,
    val sourceFingerprint: String?,
    val status: MutationStatus,
    val executedAt: Long?,
    val undoState: UndoState,
    val undoAttemptedAt: Long?,
    val error: String?,
    val undoError: String?,
)
