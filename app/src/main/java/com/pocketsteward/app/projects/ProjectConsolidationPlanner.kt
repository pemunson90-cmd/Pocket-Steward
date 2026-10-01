package com.pocketsteward.app.projects

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.executor.InMemoryFileIndex
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue

data class ProjectConsolidation(val moves: List<PlannedOperation.Move>, val held: List<FileRecord>)

/** Explicit merge of two user-chosen homes. Preserve folders and hold every naming conflict. */
object ProjectConsolidationPlanner {
    fun build(source: FileRef.Direct, destination: FileRef.Direct, sources: List<FileRecord>, destinations: List<FileRecord>): ProjectConsolidation {
        val sourcePath = source.absolutePath.trimEnd('/')
        val destinationPath = destination.absolutePath.trimEnd('/')
        require(sourcePath != destinationPath && !sourcePath.startsWith("$destinationPath/") && !destinationPath.startsWith("$sourcePath/")) { "Choose separate, non-nested project homes." }
        val byParent = sources.filter { it.stableRef != sourcePath }.groupBy { it.parentRef }
        val index = InMemoryFileIndex(destinations)
        val markerParents = sources.filter { !it.isDirectory && it.displayName == com.pocketsteward.app.storage.DirectProtection.MARKER }.mapNotNull { it.parentRef }.toSet()
        fun protected(record: FileRecord): Boolean = markerParents.any { parent -> record.stableRef == parent || record.stableRef.startsWith("$parent/") || (record.isDirectory && parent.startsWith("${record.stableRef}/")) }
        val queue = ArrayDeque<Pair<FileRef, FileRef>>()
        queue.add(source to destination)
        val visited = hashSetOf<String>()
        val moves = mutableListOf<PlannedOperation.Move>()
        val held = mutableListOf<FileRecord>()
        while (queue.isNotEmpty()) {
            val (from, to) = queue.removeFirst()
            require(visited.add(from.rawValue())) { "Repeated source folder identity." }
            for (record in byParent[from.rawValue()].orEmpty()) {
                val existing = index.caseInsensitiveMatch(to, record.displayName)
                when {
                    protected(record) -> held += record
                    existing == null -> moves += PlannedOperation.Move(parseFileRef(record.stableRef), to.child(record.displayName), "Consolidate into the project home you chose; preserve this file or intact folder.")
                    record.isDirectory && index.isDirectory(existing) -> queue.add(parseFileRef(record.stableRef) to existing)
                    else -> held += record // Same-name files stay at source, even when their sizes agree.
                }
            }
        }
        return ProjectConsolidation(moves, held)
    }
}
