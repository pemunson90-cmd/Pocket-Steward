package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.knownParentOrNull
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
        val plannedDirectories = mutableSetOf<String>()

        for (operation in operations) {
            val reason = rejectionReason(operation, index, claimedDestinations, plannedDirectories)
            if (reason != null) {
                rejected += RejectedOperation(operation, reason)
                continue
            }
            accepted += operation
            destinationOf(operation, index)?.let { destination ->
                claimedDestinations += destination.rawValue()
                if (operation is PlannedOperation.CreateDirectory) {
                    plannedDirectories += destination.rawValue()
                }
            }
        }

        return ValidatedPlan(accepted, rejected)
    }

    private fun rejectionReason(
        operation: PlannedOperation,
        index: FileIndex,
        claimedDestinations: Set<String>,
        plannedDirectories: Set<String>,
    ): String? {
        traversalRejection(operation)?.let { return it }

        return when (operation) {
            is PlannedOperation.CreateDirectory -> validateCreateDirectory(operation, index, plannedDirectories)
            is PlannedOperation.Move -> validateMove(operation, index, claimedDestinations, plannedDirectories)
            is PlannedOperation.Copy -> validateCopy(operation, index, claimedDestinations, plannedDirectories)
            is PlannedOperation.Rename -> validateRename(operation, index, claimedDestinations)
            is PlannedOperation.Trash -> validateTrash(operation, index)
            is PlannedOperation.WriteTextFile -> validateWriteTextFile(operation, index, claimedDestinations)
        }
    }

    private fun validateCreateDirectory(
        op: PlannedOperation.CreateDirectory,
        index: FileIndex,
        plannedDirectories: Set<String>,
    ): String? {
        val parentPlanned = op.parent.rawValue() in plannedDirectories
        if (!index.exists(op.parent) && !parentPlanned) return "Parent directory does not exist in the index or plan."
        if (index.exists(op.parent) && !index.isDirectory(op.parent)) return "Parent is not a directory."
        if (op.name.isBlank()) return "Directory name is blank."
        if ('/' in op.name || '\\' in op.name) return "Directory name cannot contain a path separator."

        val existing = if (parentPlanned) {
            null
        } else {
            index.caseInsensitiveMatch(op.parent, op.name)
                ?: childRef(op.parent, op.name)?.takeIf { index.exists(it) }
        }
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
        plannedDirectories: Set<String>,
    ): String? {
        if (!index.exists(op.source)) return "Source does not exist in the index."
        if (op.source == op.destination) return "Source and destination are the same."
        val destinationParent = parentOf(op.destination)
            ?: return "Cannot determine destination parent."
        val parentAuthorized = index.isDirectory(destinationParent) || destinationParent.rawValue() in plannedDirectories
        if (!parentAuthorized) {
            return "Destination parent is outside the indexed/authorized scope."
        }
        if (index.isDirectory(op.source) && isNestedUnder(op.destination, op.source, index)) {
            return "Destination is inside the source directory — recursive move."
        }
        return checkDestinationFree(op.destination, index, claimedDestinations)
    }

    private fun validateCopy(
        op: PlannedOperation.Copy,
        index: FileIndex,
        claimedDestinations: Set<String>,
        plannedDirectories: Set<String>,
    ): String? {
        if (!index.exists(op.source)) return "Source does not exist in the index."
        if (index.isDirectory(op.source)) return "Directory copy is not supported by this operation."
        if (op.source == op.destination) return "Source and destination are the same."
        val destinationParent = parentOf(op.destination)
            ?: return "Cannot determine destination parent."
        val parentAuthorized = index.isDirectory(destinationParent) || destinationParent.rawValue() in plannedDirectories
        if (!parentAuthorized) {
            return "Destination parent is outside the indexed/authorized scope."
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

        val parent = index.parentOf(op.source)
            ?: parentOf(op.source)
            ?: return "Cannot determine parent for rename."
        val destination = childRef(parent, op.newName)

        if (index.caseInsensitiveMatch(parent, op.newName, excluding = op.source) != null) {
            return "A differently-cased entry with that name already exists."
        }
        return checkDestinationFree(destination, index, claimedDestinations)
    }

    private fun validateTrash(op: PlannedOperation.Trash, index: FileIndex): String? {
        if (!index.exists(op.source)) return "Source does not exist in the index."
        return null
    }

    /**
     * Same shape as [validateCreateDirectory] except for the last rule: an
     * existing directory makes a create a harmless no-op, but an existing
     * *file* can never make a write a no-op, because the content would have
     * to be compared and a mismatch would mean overwriting. So anything at
     * the destination is a rejection, and a marker that is already there is
     * reported as "already exists" rather than silently rewritten.
     */
    private fun validateWriteTextFile(
        op: PlannedOperation.WriteTextFile,
        index: FileIndex,
        claimedDestinations: Set<String>,
    ): String? {
        if (!index.exists(op.parent)) return "Parent directory does not exist in the index."
        if (!index.isDirectory(op.parent)) return "Parent is not a directory."
        if (op.name.isBlank()) return "File name is blank."
        if (op.name.contains('/')) return "File name cannot contain a path separator."
        if (index.caseInsensitiveMatch(op.parent, op.name) != null) {
            return "A file with that name already exists here."
        }
        val destination = childRef(op.parent, op.name)
        return checkDestinationFree(destination, index, claimedDestinations)
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

    private fun childRef(parent: FileRef, name: String): FileRef =
        parent.child(name)

    private fun parentOf(ref: FileRef): FileRef? =
        ref.knownParentOrNull()

    private fun isNestedUnder(
        candidate: FileRef,
        ancestor: FileRef,
        index: FileIndex,
    ): Boolean {
        if (candidate == ancestor) return true

        if (candidate is FileRef.Direct && ancestor is FileRef.Direct) {
            val ancestorPrefix = ancestor.absolutePath.trimEnd('/') + "/"
            return candidate.absolutePath.startsWith(ancestorPrefix)
        }

        var current: FileRef? = candidate
        val seen = linkedSetOf<String>()
        while (current != null) {
            if (current == ancestor) return true
            val key = current.rawValue()
            if (!seen.add(key)) return false
            current = index.parentOf(current) ?: current.knownParentOrNull()
        }
        return false
    }

    private fun traversalRejection(operation: PlannedOperation): String? {
        val refs = when (operation) {
            is PlannedOperation.CreateDirectory -> listOf(operation.parent)
            is PlannedOperation.Move -> listOf(operation.source, operation.destination)
            is PlannedOperation.Copy -> listOf(operation.source, operation.destination)
            is PlannedOperation.Rename -> listOf(operation.source)
            is PlannedOperation.Trash -> listOf(operation.source)
            is PlannedOperation.WriteTextFile -> listOf(operation.parent)
        }
        val names = when (operation) {
            is PlannedOperation.CreateDirectory -> listOf(operation.name)
            is PlannedOperation.Rename -> listOf(operation.newName)
            is PlannedOperation.WriteTextFile -> listOf(operation.name)
            else -> emptyList()
        }
        val hasTraversal = refs.any { hasTraversalSegment(it.rawValue()) } || names.any { hasTraversalSegment(it) }
        return if (hasTraversal) "Path traversal segment ('..') is not allowed." else null
    }

    private fun hasTraversalSegment(value: String): Boolean = value.split('/').any { it == ".." }

    private fun destinationOf(
        operation: PlannedOperation,
        index: FileIndex,
    ): FileRef? = when (operation) {
        is PlannedOperation.CreateDirectory -> childRef(operation.parent, operation.name)
        is PlannedOperation.Move -> operation.destination
        is PlannedOperation.Copy -> operation.destination
        is PlannedOperation.Rename ->
            (index.parentOf(operation.source) ?: parentOf(operation.source))
                ?.let { childRef(it, operation.newName) }
        is PlannedOperation.Trash -> null
        is PlannedOperation.WriteTextFile -> childRef(operation.parent, operation.name)
    }
}
