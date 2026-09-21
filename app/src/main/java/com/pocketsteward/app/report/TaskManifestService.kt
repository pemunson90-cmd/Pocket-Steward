package com.pocketsteward.app.report

import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.parseFileRef
import java.text.DateFormat
import java.util.Date

data class TaskManifestDocument(
    val taskRunId: Long,
    val title: String,
    val text: String,
    val assertion: DuplicateAssertion,
    val entries: List<ManifestEntry>,
) {
    /** True when this run trashed nothing, so the duplicate assertion says nothing useful. */
    val hasDuplicateAssertion: Boolean get() = assertion.trashed > 0
}

/**
 * Turns the journal back into an account a person can read.
 *
 * Everything here is reconstructed from what was durably written at the time
 * each file moved — `MutationRecord` for what happened, `TaskRun.planJson`
 * for why it was asked for. Nothing is re-derived by re-examining the
 * filesystem, which is the point: the files have already moved, and a
 * manifest that describes the current state rather than the change would
 * answer a different question than the one being asked.
 */
class TaskManifestService(
    private val taskRunDao: TaskRunDao,
    private val mutationRecordDao: MutationRecordDao,
    private val onVerifiedExport: (String) -> Unit = {},
) {
    suspend fun build(taskRunId: Long): TaskManifestDocument? {
        val task = taskRunDao.getById(taskRunId) ?: return null
        val reasons = TaskManifest.reasonsBySequence(task.planJson)
        val entries = mutationRecordDao.getForTaskRun(taskRunId).map { record ->
            ManifestEntry(
                sequence = record.sequence,
                operation = record.operationType.name,
                originalPath = FileRefJournalCodec.decode(record.sourceBefore).rawValue(),
                resultPath = record.destinationAfter?.let { FileRefJournalCodec.decode(it).rawValue() },
                fingerprint = record.sourceFingerprint,
                // UNDONE counts as succeeded-then-reversed, not failed: the
                // file did move, and a manifest that called it a failure
                // would misdescribe what happened to it.
                succeeded = record.status != MutationStatus.FAILED,
                error = record.error,
                reason = reasons[record.sequence],
            )
        }
        return TaskManifestDocument(
            taskRunId = taskRunId,
            title = task.requestText,
            text = TaskManifest.render(
                title = task.requestText,
                startedAtLabel = formatTimestamp(task.startedAt),
                statusLabel = task.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                entries = entries,
            ),
            assertion = TaskManifest.duplicateAssertion(entries),
            entries = entries,
        )
    }

    /**
     * Writes the manifest into the run's own scope root, where the files it
     * describes came from.
     *
     * This goes straight to the gateway rather than through a plan, on
     * purpose. Every other write in this app mutates something the user
     * already had, so it belongs in the validate/preview/journal chain. This
     * one creates a new file that nothing else references, cannot overwrite
     * anything (the gateway refuses), and exists precisely to be readable
     * after the journal is gone — journaling it would make the account
     * depend on the thing it is insurance against.
     */
    suspend fun export(
        document: TaskManifestDocument,
        gateway: StorageGateway,
        scopeRootRef: String,
        format: ManifestFormat = ManifestFormat.MARKDOWN,
    ): ExportResult {
        // `TaskRun.scopeRootRef` holds a raw `rawValue()`, not a
        // FileRefJournalCodec string — the codec's prefixes appear only on
        // `MutationRecord.sourceBefore`/`destinationAfter`. Decoding it here
        // would throw "Unknown durable FileRef encoding" on every export.
        val parent = parseFileRef(scopeRootRef)
        if (parent !is FileRef.Direct) {
            return ExportResult.Failed("Exporting a manifest needs full file-manager access.")
        }
        val result = VerifiedManifestExporter.export(
            gateway = gateway,
            parent = parent,
            document = document,
            format = format,
        )
        if (result is ExportResult.Written) {
            onVerifiedExport(result.path)
        }
        return result
    }

    private fun formatTimestamp(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}

sealed interface ExportResult {
    data class Written(val path: String) : ExportResult
    data class Failed(val reason: String) : ExportResult
}
