package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.FileScope
import com.pocketsteward.app.storage.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read complete bounded observations first; atomically replace derived rows, never user files. */
internal class MovedFolderIndex(private val records: FileRecordDao, private val gateway: StorageGateway) {
    private companion object { const val MAX_ENTRIES = 100_000; const val QUERY_CHUNK = 400; const val MAX_BYTES = 32L * 1024 * 1024 }

    private suspend fun indexedRoot(ref: FileRef): String? = when (ref) {
        is FileRef.Child -> {
            val parent = gateway.stat(ref.parent).ref
            var after = ""
            var found: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = records.getIndexedChildren(listOf(parent.rawValue()), after, QUERY_CHUNK)
                if (page.isEmpty()) break
                for (row in page) if (row.displayName == ref.name) {
                    check(found == null) { "Indexed folder destination name is ambiguous." }
                    found = row.stableRef
                }
                after = page.last().stableRef
            }
            found
        }
        else -> ref.rawValue()
    }

    private suspend fun indexedSubtree(root: FileRef): List<String> {
        val key = indexedRoot(root) ?: return emptyList()
        val seen = linkedSetOf(key)
        var directories = listOf(key)
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val next = mutableListOf<String>()
            for (parents in directories.chunked(QUERY_CHUNK)) {
                var after = ""
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = records.getIndexedChildren(parents, after, QUERY_CHUNK)
                    if (page.isEmpty()) break
                    for (child in page) {
                        check(seen.add(child.stableRef)) { "Indexed folder has repeated or cyclic ancestry; refresh the library." }
                        check(seen.size <= MAX_ENTRIES + 1) { "Indexed folder exceeds the reconciliation limit." }
                        if (child.isDirectory) next += child.stableRef
                    }
                    after = page.last().stableRef
                }
            }
            directories = next
        }
        return seen.toList()
    }

    suspend fun remove(oldRoot: FileRef) {
        val old = indexedSubtree(oldRoot)
        currentCoroutineContext().ensureActive()
        records.replaceMovedSubtree(old, emptyList(), emptyList())
    }

    suspend fun replace(oldRoot: FileRef, newRoot: FileRef, taskScope: String) {
        val old = indexedSubtree(oldRoot)
        val rootMeta = gateway.stat(newRoot)
        check(rootMeta.isDirectory) { "Moved folder is no longer a directory; refresh the library." }
        val actualRoot = rootMeta.ref
        val rootParent = gateway.locationOf(actualRoot)?.parent ?: actualRoot.knownParentOrNull()
        val knownScopes = (records.getKnownScopeRoots() + taskScope).distinct()
        val ancestorScopes = linkedSetOf<String>()
        val scopesByIdentity = hashMapOf<String, MutableSet<String>>()
        for (scope in knownScopes) {
            currentCoroutineContext().ensureActive()
            val scopeRef = parseFileRef(scope)
            val identity = runCatching { gateway.entryIdentity(scopeRef) }.getOrNull() ?: continue
            scopesByIdentity.getOrPut(identity) { linkedSetOf() } += scope
            val member = gateway.containsInScope(actualRoot, scope)
            if (member == true || member == null && scope == taskScope && actualRoot !is FileRef.Direct) ancestorScopes += scope
        }
        data class Pending(val ref: FileRef, val parent: FileRef?, val inherited: Set<String>)
        val queue = ArrayDeque<Pending>(); queue += Pending(actualRoot, rootParent, ancestorScopes)
        val observed = mutableListOf<FileRecord>()
        val tags = mutableListOf<FileScope>()
        val visited = hashSetOf<String>()
        val timestamp = System.currentTimeMillis()
        var estimatedBytes = 0L
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val node = queue.removeFirst()
            check(visited.add(gateway.entryIdentity(node.ref))) { "Provider repeats an identity in the moved folder." }
            check(observed.size <= MAX_ENTRIES) { "Moved folder exceeds the reconciliation limit." }
            val meta = if (node.ref == actualRoot) rootMeta else gateway.stat(node.ref)
            val memberships = node.inherited + scopesByIdentity[gateway.entryIdentity(meta.ref)].orEmpty()
            estimatedBytes += 512L + 2L * (meta.ref.rawValue().length + meta.displayName.length + (node.parent?.rawValue()?.length ?: 0)) + memberships.size * 64L
            check(estimatedBytes <= MAX_BYTES) { "Moved folder metadata exceeds the bounded reconciliation memory budget." }
            observed += FileRecord(stableRef = meta.ref.rawValue(), displayName = meta.displayName, extension = meta.extension,
                mimeType = meta.mimeType, absolutePathOrUri = meta.ref.rawValue(), parentRef = node.parent?.rawValue(),
                sizeBytes = meta.sizeBytes, createdAt = meta.createdAtEpochMs, modifiedAt = meta.modifiedAtEpochMs,
                lastScannedAt = timestamp, isDirectory = meta.isDirectory, isHidden = meta.isHidden)
            memberships.forEach { tags += FileScope(meta.ref.rawValue(), it) }
            if (meta.isDirectory) {
                val children = gateway.listChildren(meta.ref)
                check(observed.size + queue.size + children.size <= MAX_ENTRIES + 1) { "Moved folder exceeds the reconciliation limit." }
                children.forEach { queue += Pending(it.ref, meta.ref, memberships) }
            }
        }
        currentCoroutineContext().ensureActive()
        records.replaceMovedSubtree((old + observed.map { it.stableRef }).distinct(), observed, tags)
    }
}
