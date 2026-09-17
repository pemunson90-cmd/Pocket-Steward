package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ScanStatus { RUNNING, COMPLETED, FAILED }

/**
 * Resume state for one in-progress or finished scan (plan Section 18). Keyed
 * directly by [scopeRootRef] since only one scan of a given root runs at a
 * time. [pendingDirectoriesJson] is the still-unvisited half of the BFS
 * queue, written after every batch flush so a process death mid-scan loses
 * at most one batch of progress, never the whole scan (see FileScanner).
 */
@Entity(tableName = "scan_checkpoints")
data class ScanCheckpoint(
    @PrimaryKey val scopeRootRef: String,
    val pendingDirectoriesJson: String,
    val processedCount: Int,
    val status: ScanStatus,
    val startedAt: Long,
    val updatedAt: Long,
)
