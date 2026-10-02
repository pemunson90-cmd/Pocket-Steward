package com.pocketsteward.app.report

import com.pocketsteward.app.data.db.MutationRecord
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.UndoState
import com.pocketsteward.app.filing.FilingOutcome
import com.pocketsteward.app.filing.FilingTaskItem
import com.pocketsteward.app.plan.DurablePlan
import com.pocketsteward.app.plan.SourcePreconditions
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.child
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A current observation, separate from the immutable approved inventory and historical journal. */
enum class FilingLocationState {
    FILED, CHECKPOINTED, COPY_PRESENT, KEPT, RETAINED_UNCERTAIN, BLOCKED,
    NOT_RUN, FAILED, RECOVERY_REQUIRED, UNDONE, MISSING, SOURCE_STILL_PRESENT,
    DESTINATION_MISSING, CHANGED, INACCESSIBLE,
}
data class FilingLocation(val item: FilingTaskItem, val state: FilingLocationState, val observedDestination: String?, val detail: String)
data class FilingRootObservation(val root: String, val newArrivals: List<String>, val unreviewed: List<String>, val error: String?)
data class FilingReconciliation(
    val checkedAt: Long,
    val locations: List<FilingLocation>,
    val roots: List<FilingRootObservation>,
    val indexedFolderDescendants: Int,
    val hasIntakeSnapshot: Boolean,
) {
    private val counts = locations.groupingBy { it.state }.eachCount()
    fun count(state: FilingLocationState): Int = counts[state] ?: 0
    val needsAttention: Int = locations.count { it.state in ATTENTION }
    val newArrivalCount: Int = roots.flatMap { it.newArrivals }.distinct().size
    val unreviewedCount: Int = roots.flatMap { it.unreviewed }.distinct().size
    val summary: String get() = "${locations.size} reviewed items checked · $needsAttention need attention · $newArrivalCount later arrivals · $unreviewedCount unreviewed entries"
    companion object {
        private val ATTENTION = setOf(FilingLocationState.NOT_RUN, FilingLocationState.FAILED, FilingLocationState.RECOVERY_REQUIRED,
            FilingLocationState.MISSING, FilingLocationState.SOURCE_STILL_PRESENT, FilingLocationState.DESTINATION_MISSING,
            FilingLocationState.CHANGED, FilingLocationState.INACCESSIBLE, FilingLocationState.BLOCKED)
    }

    fun render(): String = buildString {
        appendLine("\n## Filing verification")
        appendLine(summary)
        appendLine("Checked at epoch ms: $checkedAt")
        appendLine("Current locations and metadata are observations, not a byte-for-byte content guarantee. Later changes do not rewrite the journal.")
        appendLine("Indexed descendants covered by intact folder decisions: $indexedFolderDescendants")
        if (!hasIntakeSnapshot) appendLine("This older review did not save an intake listing; later-arrival coverage is unavailable.")
        roots.forEach { root ->
            appendLine("Folder: ${FileRefJournalCodec.decode(root.root).rawValue()}")
            root.error?.let { appendLine("  Verification unavailable: $it") }
            root.newArrivals.forEach { appendLine("  Later arrival: ${FileRefJournalCodec.decode(it).rawValue()}") }
            root.unreviewed.forEach { appendLine("  Unreviewed at intake: ${FileRefJournalCodec.decode(it).rawValue()}") }
        }
        locations.forEach { location ->
            appendLine("- ${location.item.displayName}: ${location.state.name}")
            appendLine("  Approved: ${location.item.outcome.name}; ${location.item.reason}")
            appendLine("  Source: ${FileRefJournalCodec.decode(location.item.source).rawValue()}")
            location.observedDestination?.let { appendLine("  Destination: ${FileRefJournalCodec.decode(it).rawValue()}") }
            appendLine("  ${location.detail}")
        }
    }
}

object FilingReconciler {
    suspend fun check(plan: DurablePlan, records: List<MutationRecord>, gateway: StorageGateway): FilingReconciliation? {
        val inventory = plan.filingInventory ?: return null
        inventory.validate(plan.operations)
        val recordsBySequence = records.groupBy { it.sequence }
        val locations = inventory.items.map { item ->
            coroutineContext.ensureActive()
            val matching = item.operationSequence?.let { recordsBySequence[it].orEmpty() }
            try {
                if (matching != null && matching.size > 1) {
                    FilingLocation(item, FilingLocationState.RECOVERY_REQUIRED, null, "Conflicting journal records require recovery review.")
                } else inspect(item, matching?.singleOrNull(), plan, gateway)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { FilingLocation(item, FilingLocationState.INACCESSIBLE, item.destination, "Current storage access could not verify this item.") }
        }
        val knownSources = inventory.items.mapTo(hashSetOf()) { it.source }
        val knownRoots = inventory.intake?.roots.orEmpty().mapTo(hashSetOf()) { it.root }
        // Expected created checkpoint folders are results of this run, not new arrivals.
        val completedResults = records.filter { it.status == MutationStatus.COMMITTED || it.status == MutationStatus.UNDONE }
            .mapNotNullTo(hashSetOf()) { it.destinationAfter }
        val roots = inventory.intake?.roots.orEmpty().map { root ->
            coroutineContext.ensureActive()
            try {
                val children = gateway.listChildren(FileRefJournalCodec.decode(root.root))
                require(children.size <= 100_000)
                val current = children.map { FileRefJournalCodec.encode(it.ref) }.toSet()
                val baseline = root.children.toHashSet()
                // Include actual result URIs for providers that resolve typed child refs.
                val excluded = knownSources + knownRoots + completedResults
                FilingRootObservation(root.root,
                    if (root.error == null) (current - baseline - excluded).toList() else emptyList(),
                    (baseline.intersect(current) - knownSources - knownRoots).toList(), root.error)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { FilingRootObservation(root.root, emptyList(), emptyList(), "This folder could not be listed during verification.") }
        }
        return FilingReconciliation(System.currentTimeMillis(), locations, roots, inventory.indexedFolderDescendants, inventory.intake != null)
    }

    private suspend fun inspect(item: FilingTaskItem, record: MutationRecord?, plan: DurablePlan, gateway: StorageGateway): FilingLocation {
        fun result(state: FilingLocationState, detail: String, destination: String? = record?.destinationAfter ?: item.destination) =
            FilingLocation(item, state, destination, detail)
        val source = FileRefJournalCodec.decode(item.source)
        if (item.operationSequence == null) {
            if (!gateway.exists(source)) return result(FilingLocationState.MISSING, "The item is no longer present at its reviewed source.")
            return result(when (item.outcome) {
                FilingOutcome.KEEP -> FilingLocationState.KEPT
                FilingOutcome.RETAINED_UNCERTAIN -> FilingLocationState.RETAINED_UNCERTAIN
                else -> FilingLocationState.BLOCKED
            }, "Present at the reviewed source. ${item.reason}")
        }
        if (record == null) return result(FilingLocationState.NOT_RUN, "No operation result has been journaled yet.")
        if (record.sourceBefore != item.source || record.operationType.name != (if (item.outcome == FilingOutcome.COPY_RETAINED) "COPY" else "MOVE")) {
            return result(FilingLocationState.RECOVERY_REQUIRED, "Journal identity does not match the approved inventory.", null)
        }
        if (record.status == MutationStatus.UNDONE || record.undoState == UndoState.UNDONE) {
            val sourcePresent = gateway.exists(source)
            return result(if (sourcePresent) FilingLocationState.UNDONE else FilingLocationState.MISSING,
                "Undo is recorded; ${if (sourcePresent) "the source is present" else "the source could not be found"}.")
        }
        when (record.status) {
            MutationStatus.PENDING, MutationStatus.NEEDS_REVIEW -> return result(FilingLocationState.RECOVERY_REQUIRED, "The journal requires recovery review; location alone does not prove completion.")
            MutationStatus.FAILED -> return result(FilingLocationState.FAILED, record.error ?: "The operation failed.")
            else -> Unit
        }
        val actualDestination = record.destinationAfter ?: return result(FilingLocationState.RECOVERY_REQUIRED, "The committed record has no destination.")
        val destination = FileRefJournalCodec.decode(actualDestination)
        if (!gateway.exists(destination)) return result(FilingLocationState.DESTINATION_MISSING, "The recorded destination could not be found.")
        val metadata = gateway.stat(destination)
        if (metadata.isDirectory != item.directory) return result(FilingLocationState.CHANGED, "The destination type differs from the approved item.")
        val expected = plan.sourcePreconditions[item.operationSequence]
        if (expected != null) {
            val current = SourcePreconditions.capture(gateway, destination)
            // A move can change the root directory timestamp; child names/metadata must still agree.
            val matches = if (item.directory) expected.directoryDigest == current.directoryDigest && expected.directoryEntryCount == current.directoryEntryCount && expected.sizeBytes == current.sizeBytes
                else expected.sizeBytes == current.sizeBytes
            if (!matches) return result(FilingLocationState.CHANGED, "Destination metadata differs from the approved source; inspect it before further changes.")
        }
        val sourcePresent = gateway.exists(source)
        if (item.outcome != FilingOutcome.COPY_RETAINED && sourcePresent) return result(FilingLocationState.SOURCE_STILL_PRESENT, "Both the original source and recorded destination are present; review before taking action.")
        if (item.outcome == FilingOutcome.COPY_RETAINED && !sourcePresent) return result(FilingLocationState.MISSING, "The copied destination is present but the retained original could not be found.")
        return result(when (item.outcome) {
            FilingOutcome.CHECKPOINT -> FilingLocationState.CHECKPOINTED
            FilingOutcome.COPY_RETAINED -> FilingLocationState.COPY_PRESENT
            else -> FilingLocationState.FILED
        }, "Recorded destination is present and checked against available approval metadata.")
    }
}
