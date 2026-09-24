package com.pocketsteward.app.plan

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException

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

/**
 * Source identities captured at the moment a plan is put in front of the
 * user for review, keyed by [FileRef.rawValue].
 *
 * The approval-time check alone compared the live file with its scan-index
 * record. The background library refresh keeps those records current, and
 * the Files tab re-syncs them immediately before enqueueing, so a file edited
 * after the user reviewed the plan could match its refreshed record and pass.
 * This snapshot is what the user actually saw; the executor refuses any
 * reviewed source whose size or modified time no longer matches it.
 */
object ReviewedSources {
    /** The file whose identity the user approved for this operation, if it has one. */
    fun sourceOf(operation: PlannedOperation): FileRef? = when (operation) {
        is PlannedOperation.Move -> operation.source
        is PlannedOperation.Copy -> operation.source
        is PlannedOperation.Rename -> operation.source
        is PlannedOperation.Trash -> operation.source
        is PlannedOperation.CreateDirectory,
        is PlannedOperation.WriteTextFile,
        -> null
    }

    /**
     * Metadata only (no hashing), so it stays cheap for large cleanup plans.
     * Sources that cannot be read are left out; the executor then falls back
     * to its previous scan-index comparison for them.
     */
    suspend fun capture(
        operations: List<PlannedOperation>,
        gateway: StorageGateway,
    ): Map<String, SourcePrecondition> {
        val captured = linkedMapOf<String, SourcePrecondition>()
        for (operation in operations) {
            val source = sourceOf(operation) ?: continue
            val key = source.rawValue()
            if (key in captured) continue
            val metadata = try {
                if (!gateway.exists(source)) continue
                gateway.stat(source)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                continue
            }
            SourcePreconditions.from(metadata)?.let { captured[key] = it }
        }
        return captured
    }

    /**
     * Why a reviewed source must not be touched, or null when it still
     * matches what the user reviewed. [current] is null when the source is
     * now a directory or unreadable as a file.
     */
    fun failure(
        key: String,
        reviewed: SourcePrecondition,
        exists: Boolean,
        current: SourcePrecondition?,
    ): String? = when {
        !exists -> "Source disappeared after you reviewed the plan; review it again: $key"
        current == null -> "Source type changed after you reviewed the plan; review it again: $key"
        !SourcePreconditions.matches(reviewed, current) ->
            "Source changed after you reviewed the plan; review it again: $key"
        else -> null
    }
}
