package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue

data class RejectedOperation(val operation: PlannedOperation, val reason: String)

data class ValidatedPlan(
    val accepted: List<PlannedOperation>,
    val rejected: List<RejectedOperation>,
)

/**
 * Plan Section 12. Runs as a single pre-pass over the whole plan before any
 * mutation is attempted — never interleaved with execution — and never
 * needs a model: every rule here is a deterministic check against
 * [FileIndex] and the operation's own shape. An operation that fails
 * validation is simply excluded from [ValidatedPlan.accepted]; the rest of
 * the plan still proceeds; this is what lets Decision 5 ("leave this alone
 * is a correct result") apply at the validator layer, not just the UI.
 */
object PlanValidator {

    fun validate(operations: List<PlannedOperation>, index: FileIndex): ValidatedPlan {
        val accepted = mutableListOf<PlannedOperation>()
        val rejected = mutableListOf<RejectedOperation>()

        // Destinations already claimed by an earlier accepted operation in
        // this same plan — a per-plan collision the index alone can't see,
        // since it reflects storage before any of these operations ran.
        val claimedDestinations = mutableSetOf<String>()

        for (operation in operations) {
            val reason = rejectionReason(operation, index, claimedDestinations)
            if (reason != null) {
                rejected += RejectedOperation(operation, reason)
                continue
            }
            accepted += operation
            destinationOf(operation)?.let { claimedDestinations += it.rawValue() }
        }

        return ValidatedPlan(accepted, rejected)
    }

    private fun rejectionReason(
        operation: PlannedOperation,
        index: FileIndex,
        claimedDestinations: Set<String>,
    ): String? {
        traversalRejection(operation)?.let { return it }

        return when (operation) {
            is PlannedOperation.CreateDirectory -> validateCreateDirectory(operation, index)
            is PlannedOperation.Move -> validateMove(operation, index, claimedDestinations)
            is PlannedOperation.Rename -> validateRename(operation, index, claimedDestinations)
            is PlannedOperation.Trash -> validateTrash(operation, index)
        }
    }

    private fun validateCreateDirectory(op: PlannedOperation.CreateDirectory, index: FileIndex): String? {
        if (!index.exists(op.parent)) return "Parent directory does not exist in the index."
        if (!index.isDirectory(op.parent)) return "Parent is not a directory."
        if (op.name.isBlank()) return "Directory name is blank."

        val existing = index.caseInsensitiveMatch(op.parent, op.name)
            ?: directRef(op.parent, op.name)?.takeIf { index.exists(it) }
        // Creating a directory that already exists as a directory is a
        // harmless no-op, not a collision — repeated organize runs need
        // this to not fail every time after the first.
        return when {
            existing == null -> null
            index.isDirectory(existing) -> null
            else -> "A file already exists with that name."
        }
    }

    private fun validateMove(
        op: PlannedOperation.Move,
        index: FileIndex,
        claimedDestinations: Set<String>,
    ): String? {
        if (!index.exists(op.source)) return "Source does not exist in the index."
        if (op.source == op.destination) return "Source and destination are the same."
        if (index.isDirectory(op.source) && isNestedUnder(op.destination, op.source)) {
            return "Destination is inside the source directory — recursive move."
        }
        return checkDestinationFree(op.destination, index, claimedDestinations)
    }

    private fun validateRename(
        op: PlannedOperation.Rename,
        index: FileIndex,
        claimedDestinations: Set<String>,
    ): String? {
        if (!index.exists(op.source)) return "Source does not exist in the index."
        if (op.newName.isBlank()) return "New name is blank."

        val parent = parentOf(op.source) ?: return "Cannot determine parent for rename."
        val destination = directRef(parent, op.newName) ?: op.source

        if (index.caseInsensitiveMatch(parent, op.newName, excluding = op.source) != null) {
            return "A differently-cased entry with that name already exists."
        }
        return checkDestinationFree(destination, index, claimedDestinations)
    }

    private fun validateTrash(op: PlannedOperation.Trash, index: FileIndex): String? {
        if (!index.exists(op.source)) return "Source does not exist in the index."
        return null
    }

    private fun checkDestinationFree(
        destination: FileRef,
        index: FileIndex,
        claimedDestinations: Set<String>,
    ): String? {
        if (destination.rawValue() in claimedDestinations) {
            return "Destination is already claimed by an earlier operation in this plan."
        }
        if (index.exists(destination)) {
            return "Destination already exists — no overwrite without explicit policy."
        }
        return null
    }

    /** [name] as a direct child of [parent], only where that's derivable (Direct paths). */
    private fun directRef(parent: FileRef, name: String): FileRef? = when (parent) {
        is FileRef.Direct -> FileRef.Direct("${parent.absolutePath.trimEnd('/')}/$name")
        is FileRef.Saf -> null
    }

    private fun parentOf(ref: FileRef): FileRef? = when (ref) {
        is FileRef.Direct -> {
            val parentPath = ref.absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
            if (parentPath.isBlank()) null else FileRef.Direct(parentPath)
        }
        is FileRef.Saf -> null // Not decomposable from the URI alone.
    }

    private fun isNestedUnder(candidate: FileRef, ancestor: FileRef): Boolean {
        if (candidate !is FileRef.Direct || ancestor !is FileRef.Direct) return false
        val ancestorPrefix = ancestor.absolutePath.trimEnd('/') + "/"
        return candidate.absolutePath.startsWith(ancestorPrefix)
    }

    private fun traversalRejection(operation: PlannedOperation): String? {
        val refs = when (operation) {
            is PlannedOperation.CreateDirectory -> listOf(operation.parent)
            is PlannedOperation.Move -> listOf(operation.source, operation.destination)
            is PlannedOperation.Rename -> listOf(operation.source)
            is PlannedOperation.Trash -> listOf(operation.source)
        }
        val names = when (operation) {
            is PlannedOperation.CreateDirectory -> listOf(operation.name)
            is PlannedOperation.Rename -> listOf(operation.newName)
            else -> emptyList()
        }
        val hasTraversal = refs.any { hasTraversalSegment(it.rawValue()) } || names.any { hasTraversalSegment(it) }
        return if (hasTraversal) "Path traversal segment ('..') is not allowed." else null
    }

    private fun hasTraversalSegment(value: String): Boolean = value.split('/').any { it == ".." }

    private fun destinationOf(operation: PlannedOperation): FileRef? = when (operation) {
        is PlannedOperation.Move -> operation.destination
        else -> null
    }
}
