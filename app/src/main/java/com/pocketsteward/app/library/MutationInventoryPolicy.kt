package com.pocketsteward.app.library

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child

/** A reviewed mutation can invalidate inventory, but these paths never authorize another mutation. */
object MutationInventoryPolicy {
    data class EvidenceChanges(val refs: Set<String>, val full: Boolean)

    /** Invalidate exact changed identities/subtrees; opaque prospective destinations need a full fence. */
    fun evidenceChanges(operations: List<PlannedOperation>, extra: List<FileRef> = emptyList()): EvidenceChanges {
        val affected = operations.flatMap { operation -> when (operation) {
            is PlannedOperation.Move -> listOf(operation.source, operation.destination)
            is PlannedOperation.Copy -> listOf(operation.destination)
            is PlannedOperation.Rename -> listOf(operation.source, operation.source.let { source ->
                if (source is FileRef.Direct) FileRef.Direct(java.io.File(java.io.File(source.absolutePath).parentFile, operation.newName).path) else source })
            is PlannedOperation.Trash -> listOf(operation.source)
            is PlannedOperation.CreateDirectory -> listOf(operation.parent.child(operation.name))
            is PlannedOperation.WriteTextFile -> listOf(operation.parent.child(operation.name))
        } } + extra
        return EvidenceChanges(affected.filterIsInstance<FileRef.Direct>().mapTo(hashSetOf()) { it.absolutePath },
            operations.isEmpty() || affected.any { it !is FileRef.Direct })
    }

    fun directories(operations: List<PlannedOperation>, trashDestinations: List<FileRef> = emptyList()): Set<String> = buildSet {
        fun parent(ref: FileRef) {
            val direct = ref as? FileRef.Direct ?: return
            val path = direct.absolutePath.substringBeforeLast('/', "")
            if (path.isNotBlank()) add(path)
        }
        trashDestinations.forEach(::parent)
        for (operation in operations) when (operation) {
            is PlannedOperation.Move -> { parent(operation.source); parent(operation.destination) }
            is PlannedOperation.Copy -> parent(operation.destination)
            is PlannedOperation.Rename -> parent(operation.source)
            is PlannedOperation.Trash -> parent(operation.source)
            is PlannedOperation.CreateDirectory -> parent(operation.parent.child(operation.name))
            is PlannedOperation.WriteTextFile -> parent(operation.parent.child(operation.name))
        }
    }
}
