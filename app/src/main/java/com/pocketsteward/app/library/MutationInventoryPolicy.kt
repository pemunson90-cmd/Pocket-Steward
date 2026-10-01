package com.pocketsteward.app.library

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child

/** A reviewed mutation can invalidate inventory, but these paths never authorize another mutation. */
object MutationInventoryPolicy {
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
