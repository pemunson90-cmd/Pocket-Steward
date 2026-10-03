package com.pocketsteward.app.filing

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.ReviewedSources
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Read-only intake snapshots never grant permission to mutate any of these entries. */
data class FilingIntakeRoot(val root: String, val children: List<String>, val error: String? = null)
data class FilingIntakeSnapshot(val roots: List<FilingIntakeRoot>, val capturedAt: Long)

data class FilingTaskItem(
    val source: String,
    val displayName: String,
    val directory: Boolean,
    val outcome: FilingOutcome,
    val reason: String,
    val operationSequence: Int? = null,
    val destination: String? = null,
    val originalLocation: String? = null,
)

data class FilingTaskInventory(
    val items: List<FilingTaskItem>,
    val intake: FilingIntakeSnapshot?,
    val indexedFolderDescendants: Int,
) {
    /** The inventory describes the approved selection; it cannot add or change operations. */
    fun validate(operations: List<PlannedOperation>) {
        require(items.size <= 100_000 && indexedFolderDescendants >= 0)
        require(items.map { it.source }.distinct().size == items.size) { "Duplicate inventory sources." }
        require(items.none { it.outcome == FilingOutcome.NEEDS_DECISION }) { "Incomplete filing inventory." }
        items.forEach { item ->
            FileRefJournalCodec.decode(item.source)
            item.originalLocation?.let { require(FileRefJournalCodec.decode(it) is com.pocketsteward.app.storage.FileRef.Child) }
            val selected = item.outcome in setOf(FilingOutcome.DESTINATION, FilingOutcome.CHECKPOINT, FilingOutcome.COPY_RETAINED)
            require(selected == (item.operationSequence != null)) { "Inventory selection does not match its outcome." }
            if (selected) {
                val operation = operations.getOrNull(requireNotNull(item.operationSequence))
                require(operation is PlannedOperation.Move || operation is PlannedOperation.Copy)
                require(FileRefJournalCodec.encode(requireNotNull(ReviewedSources.sourceOf(operation))) == item.source)
                val destination = when (operation) {
                    is PlannedOperation.Move -> operation.destination
                    is PlannedOperation.Copy -> operation.destination
                    else -> error("Unsupported filing operation.")
                }
                require(item.destination == FileRefJournalCodec.encode(destination))
                require((operation is PlannedOperation.Copy) == (item.outcome == FilingOutcome.COPY_RETAINED))
            } else require(item.destination == null)
        }
        val filingOperations = operations.mapIndexedNotNull { sequence, operation ->
            if (operation is PlannedOperation.Move || operation is PlannedOperation.Copy) sequence else null
        }.toSet()
        require(items.mapNotNull { it.operationSequence }.toSet() == filingOperations) { "An approved source is missing from the inventory." }
        intake?.let { snapshot ->
            require(snapshot.roots.size <= 1024 && snapshot.roots.map { it.root }.distinct().size == snapshot.roots.size)
            require(snapshot.roots.sumOf { it.children.size.toLong() } <= 200_000)
            snapshot.roots.forEach { root ->
                FileRefJournalCodec.decode(root.root)
                require(root.children.distinct().size == root.children.size)
                root.children.forEach { FileRefJournalCodec.decode(it) }
            }
        }
    }
}

object FilingTaskInventoryBuilder {
    fun build(
        review: FilingInventory,
        approved: List<PlannedOperation>,
        intake: FilingIntakeSnapshot?,
        indexedFolderDescendants: Int,
        reviewedSources: Map<String, com.pocketsteward.app.plan.SourcePrecondition> = emptyMap(),
    ): FilingTaskInventory {
        val sequences = approved.mapIndexedNotNull { sequence, op ->
            ReviewedSources.sourceOf(op)?.rawValue()?.let { it to sequence }
        }.toMap()
        return FilingTaskInventory(review.entries.map { entry ->
            val selected = entry.outcome in setOf(FilingOutcome.DESTINATION, FilingOutcome.CHECKPOINT, FilingOutcome.COPY_RETAINED)
            val sequence = if (selected) requireNotNull(sequences[entry.item.sourceRef]) else null
            val destination = sequence?.let { when (val op = approved[it]) {
                is PlannedOperation.Move -> op.destination
                is PlannedOperation.Copy -> op.destination
                else -> error("Unsupported filing operation.")
            } }
            FilingTaskItem(
                FileRefJournalCodec.encode(parseFileRef(entry.item.sourceRef)), entry.item.displayName,
                entry.item.isDirectory, entry.outcome, entry.reason, sequence,
                destination?.let(FileRefJournalCodec::encode),
                reviewedSources[entry.item.sourceRef]?.location,
            )
        }, intake, indexedFolderDescendants).also { it.validate(approved) }
    }

    suspend fun captureIntake(gateway: StorageGateway, roots: List<String>): FilingIntakeSnapshot {
        require(roots.distinct().size <= 1024)
        val pending = ArrayDeque(roots.distinct())
        val seen = hashSetOf<String>()
        val snapshots = mutableListOf<FilingIntakeRoot>()
        var total = 0L
        while (pending.isNotEmpty()) {
            val raw = pending.removeFirst()
            if (!seen.add(raw)) continue
            require(seen.size <= 1024)

            coroutineContext.ensureActive()
            val ref = parseFileRef(raw)
            val encoded = FileRefJournalCodec.encode(ref)
            try {
                val children = gateway.listChildren(ref)
                require(children.size <= 100_000) { "Folder exceeds the supported inventory size." }
                total += children.size
                require(total <= 200_000) { "Intake exceeds the supported inventory size." }
                children.filter { it.isDirectory && it.displayName.equals("Uncertain", true) }
                    .forEach { pending.add(it.ref.rawValue()) }
                snapshots += FilingIntakeRoot(encoded, children.map { FileRefJournalCodec.encode(it.ref) }.distinct())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshots += FilingIntakeRoot(encoded, emptyList(), "This folder could not be listed at review time.")
            }
        }
        return FilingIntakeSnapshot(snapshots, System.currentTimeMillis())
    }
}
