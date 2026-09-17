package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED, UNDONE }

/**
 * One organize/cleanup run, from request text to completion (plan Section 15).
 * [planJson] is the validated plan that was actually approved and executed,
 * kept verbatim so a later undo or audit doesn't depend on re-deriving it.
 */
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
)
