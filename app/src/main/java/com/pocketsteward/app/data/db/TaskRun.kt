package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pocketsteward.app.storage.StorageAccessMode

enum class TaskRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW,
    UNDOING,
    UNDONE,
    UNDO_PARTIAL,
}

/** One organize/cleanup run, including enough scope information to undo it after an app restart. */
@Entity(tableName = "task_runs")
data class TaskRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestText: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: TaskRunStatus,
    val scanSnapshotId: Long?,
    val planJson: String,
    val summary: String?,
    val scopeRootRef: String,
    val storageAccessMode: StorageAccessMode,
    val undoCompletedAt: Long?,
)
