package com.pocketsteward.app.executor

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.rawValue

/** Inspect only affected destination names and their ancestors; never silently truncate a home. */
object DestinationSnapshot {
    suspend fun load(gateway: StorageGateway, root: FileRef.Direct, operations: List<PlannedOperation>, maxEntries: Int = 100_000): List<FileEntry> {
        require(gateway.exists(root) && gateway.stat(root).isDirectory) { "The authorized destination folder is unavailable. Re-select it and rebuild." }
        val rootPath = root.absolutePath.trimEnd('/')
        val wanted = linkedMapOf<String, MutableSet<String>>()
        for (operation in operations) {
            val destination = when (operation) {
                is PlannedOperation.Move -> operation.destination
                is PlannedOperation.Copy -> operation.destination
                is PlannedOperation.CreateDirectory -> operation.parent.child(operation.name)
                is PlannedOperation.WriteTextFile -> operation.parent.child(operation.name)
                is PlannedOperation.Rename -> (operation.source as? FileRef.Direct)?.absolutePath?.substringBeforeLast('/')?.let { FileRef.Direct(it).child(operation.newName) }
                is PlannedOperation.Trash -> null
            } as? FileRef.Direct ?: continue
            var path = destination.absolutePath.trimEnd('/')
            while (path.startsWith("$rootPath/") && path != rootPath) {
                val parent = path.substringBeforeLast('/')
                wanted.getOrPut(parent) { linkedSetOf() }.add(path.substringAfterLast('/').lowercase(java.util.Locale.ROOT))
                path = parent
            }
        }
        val requestedByParentAndName = wanted.keys.groupBy { it.substringBeforeLast('/') + "\u0000" + it.substringAfterLast('/').lowercase(java.util.Locale.ROOT) }
        val aliases = hashMapOf(rootPath to root)
        val out = linkedMapOf<String, FileEntry>()
        for ((path, names) in wanted.entries.sortedBy { it.key.count { c -> c == '/' } }) {
            val parent = aliases[path] ?: continue // A missing ancestor is created by the reviewed plan.
            val children = gateway.listChildren(parent).filter { it.displayName.lowercase(java.util.Locale.ROOT) in names }
            require(children.filter { it.isDirectory }.groupBy { it.displayName.lowercase(java.util.Locale.ROOT) }.values.none { it.size > 1 }) { "Destination has ambiguous folder names differing only by capitalization. Choose a distinct home." }
            for (child in children) {
                require(out.size < maxEntries) { "Destination inspection exceeds the supported plan size. Split this review into smaller batches." }
                out[child.ref.rawValue()] = child
                if (child.isDirectory) {
                    val requestedPaths = requestedByParentAndName[path + "\u0000" + child.displayName.lowercase(java.util.Locale.ROOT)].orEmpty()
                    requestedPaths.forEach { requested -> aliases[requested] = child.ref as FileRef.Direct }
                }
            }
        }
        return out.values.toList()
    }
}
