package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@Dao
interface TaskRunDao {
    @Insert
    suspend fun insert(taskRun: TaskRun): Long

    @Transaction
    suspend fun insertWhenIdle(taskRun: TaskRun): Long {
        require(getRunsNeedingRecovery().none { it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.UNDOING }) {
            "Another file task is active. Pause or finish it before starting another."
        }
        return insert(taskRun)
    }

    @Transaction
    suspend fun activateWhenIdle(taskRun: TaskRun) {
        require(getRunsNeedingRecovery().none { it.id != taskRun.id && (it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.UNDOING) }) {
            "Another file task is active. Pause or finish it before resuming or undoing."
        }
        update(taskRun)
    }

    @Update
    suspend fun update(taskRun: TaskRun)

    // Android cursor windows are bounded. A 16k-operation plan can exceed one
    // window; retrieve its UTF-8 bytes in bounded pieces within one snapshot.
    @Transaction
    suspend fun getById(id: Long): TaskRun? {
        val metadata = metadataById(id) ?: return null
        val length = planByteLength(id) ?: error("Task disappeared while reading its plan.")
        val bytes = ByteArrayOutputStream()
        var offset = 1L
        while (offset <= length) {
            val size = minOf(65_536L, length - offset + 1).toInt()
            val part = planChunk(id, offset, size) ?: error("Task plan could not be read completely.")
            check(part.size == size) { "Task plan read was incomplete." }
            bytes.write(part)
            offset += part.size
        }
        return metadata.copy(planJson = bytes.toString(StandardCharsets.UTF_8.name()))
    }

    @Query("SELECT id, requestText, startedAt, completedAt, status, scanSnapshotId, '' AS planJson, summary, scopeRootRef, storageAccessMode, undoCompletedAt FROM task_runs WHERE id = :id")
    suspend fun metadataById(id: Long): TaskRun?

    @Query("SELECT substr(CAST(planJson AS BLOB), :offset, :size) FROM task_runs WHERE id = :id")
    suspend fun planChunk(id: Long, offset: Long, size: Int): ByteArray?

    @Query("SELECT length(CAST(planJson AS BLOB)) FROM task_runs WHERE id = :id")
    suspend fun planByteLength(id: Long): Long?

    @Query("SELECT id FROM task_runs WHERE status IN (:statuses) ORDER BY startedAt ASC")
    suspend fun idsByStatus(statuses: List<String>): List<Long>

    @Query("SELECT id FROM task_runs WHERE id > :afterId ORDER BY id ASC")
    suspend fun idsAfter(afterId: Long): List<Long>

    @Query("SELECT COALESCE(MAX(id), 0) FROM task_runs")
    suspend fun latestTaskId(): Long

    @Query("SELECT COUNT(*) FROM task_runs WHERE status IN ('RUNNING', 'UNDOING')")
    suspend fun busyCount(): Int

    @Query("SELECT COUNT(*) FROM task_runs WHERE status IN ('RUNNING', 'UNDOING')")
    fun observeBusyCount(): Flow<Int>

    @Transaction
    suspend fun plansAfter(afterId: Long): List<QueuedPlanIdentity> =
        idsAfter(afterId).mapNotNull { id -> getById(id)?.let { QueuedPlanIdentity(id, it.planJson) } }

    @Query("SELECT substr(requestText, 1, 20000) AS request, status, startedAt, completedAt, substr(summary, 1, 20000) AS summary FROM task_runs ORDER BY startedAt DESC LIMIT 1000")
    suspend fun portableSummaries(): List<com.pocketsteward.app.backup.ArchivedTaskSummary>

    // List screens never load/decode saved operations. SQL returns small
    // presentation fields, even when a persisted plan is several megabytes.
    // These hints never authorize resume: the executor still decodes/validates
    // the entire immutable approved plan before running it.
    @Query("""
        SELECT id, substr(requestText, 1, 20000) AS requestText, startedAt, completedAt, status,
            substr(summary, 1, 20000) AS summary,
            instr(planJson, char(10) || '@psplan' || char(9)) > 0 AS hasDurablePlan,
            CASE WHEN status IN ('RUNNING', 'CANCELLED') THEN
                (length(planJson) - length(replace(planJson, char(10) || '@psop' || char(9), ''))) / 7
                ELSE 0 END AS operationCount
        FROM task_runs ORDER BY startedAt DESC
    """)
    fun observeOverviews(): Flow<List<TaskRunOverview>>


    @Transaction
    suspend fun getRunning(): List<TaskRun> =
        idsByStatus(listOf("RUNNING")).mapNotNull { getById(it) }

    @Query(
        "UPDATE task_runs SET status = 'CANCELLED', completedAt = :completedAt, summary = :summary " +
            "WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun markRunningPaused(
        id: Long,
        completedAt: Long,
        summary: String,
    ): Int

    @Transaction
    suspend fun getRunsNeedingRecovery(): List<TaskRun> =
        idsByStatus(listOf("RUNNING", "NEEDS_REVIEW", "UNDOING", "UNDO_PARTIAL")).mapNotNull { getById(it) }
}

data class QueuedPlanIdentity(val id: Long, val planJson: String)

/** A display projection, deliberately incapable of being written back as an approved task. */
data class TaskRunOverview(
    val id: Long,
    val requestText: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: TaskRunStatus,
    val summary: String?,
    val hasDurablePlan: Boolean,
    val operationCount: Int,
)
