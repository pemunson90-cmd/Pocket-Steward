package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One filesystem mutation and its inverse (plan Section 15). [status] must be
 * written as PENDING *before* the filesystem call and flipped to COMMITTED
 * only after it succeeds, so a crash between the two leaves a row that
 * reconciliation on restart can detect as "outcome unknown" rather than
 * silently losing the operation.
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
    val undoState: String?,
    val error: String?,
)
