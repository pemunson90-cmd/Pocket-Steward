package com.pocketsteward.app.filing

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.plan.ReviewedSources
import com.pocketsteward.app.storage.rawValue

enum class FilingOutcome { DESTINATION, COPY_RETAINED, CHECKPOINT, RETAINED_UNCERTAIN, KEEP, BLOCKED, NEEDS_DECISION }

data class FilingInventoryEntry(val item: FilingReviewItem, val outcome: FilingOutcome, val reason: String)

data class FilingInventory(val entries: List<FilingInventoryEntry>) {
    private val counts = entries.groupingBy { it.outcome }.eachCount()
    fun count(outcome: FilingOutcome): Int = counts[outcome] ?: 0
    val pendingRefs: Set<String> = entries.asSequence()
        .filter { it.outcome == FilingOutcome.NEEDS_DECISION }.map { it.item.sourceRef }.toSet()
    val complete: Boolean get() = pendingRefs.isEmpty()
}

/** One visible outcome per reviewed source. Deselection never authorizes a fallback move. */
object FilingInventoryPolicy {
    fun build(
        filing: FilingReviewPresentation,
        accepted: List<PlannedOperation>,
        selectedIndices: Set<Int>,
        rejected: List<RejectedOperation>,
    ): FilingInventory {
        val items = (filing.groups.asSequence().flatMap { it.items.asSequence() } + filing.unresolved.asSequence())
            .distinctBy { it.sourceRef }.toList()
        val sourceOperations = accepted.mapIndexedNotNull { index, op ->
            ReviewedSources.sourceOf(op)?.rawValue()?.let { it to index }
        }.toMap()
        val blocked = rejected.mapNotNull { rejection ->
            ReviewedSources.sourceOf(rejection.operation)?.rawValue()?.let { it to rejection.reason }
        }.toMap()
        val checkpointRefs = filing.checkpointGroups.asSequence().flatMap { it.items.asSequence() }
            .map { it.sourceRef }.toHashSet()
        return FilingInventory(items.map { item ->
            val index = sourceOperations[item.sourceRef]
            val (outcome, reason) = when {
                index != null && index in selectedIndices -> {
                    if (accepted[index] is PlannedOperation.Copy) FilingOutcome.COPY_RETAINED to "Copy to the selected destination; the original stays here."
                    else if (item.sourceRef in checkpointRefs) FilingOutcome.CHECKPOINT to "Move to Uncertain for later review."
                    else FilingOutcome.DESTINATION to "Move to the selected destination."
                }
                item.sourceRef in filing.heldSourceRefs.orEmpty() -> FilingOutcome.KEEP to "You chose to keep this item here."
                item.sourceRef in blocked -> FilingOutcome.BLOCKED to blocked.getValue(item.sourceRef)
                item.sourceRef in filing.retainedUncertainSourceRefs.orEmpty() ->
                    FilingOutcome.RETAINED_UNCERTAIN to "Already in Uncertain; stays here until a destination is approved."
                index != null -> FilingOutcome.NEEDS_DECISION to "Choose its destination, move to Uncertain, or keep here."
                else -> FilingOutcome.BLOCKED to "No safe move is available. Assign a destination or rebuild the review."
            }
            FilingInventoryEntry(item, outcome, reason)
        })
    }
}
