package com.pocketsteward.app.projects

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.DirectProtection
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import java.util.Locale

data class ProjectConsolidation(
    val moves: List<PlannedOperation.Move>,
    val held: List<FileRecord>,
    val heldReasons: Map<String, String> = emptyMap(),
)

/** Explicit, reviewed merge. Parent identities work for both paths and opaque provider IDs. */
object ProjectConsolidationPlanner {
    fun build(source: FileRef, destination: FileRef, sources: List<FileRecord>, destinations: List<FileRecord>,
        checkCancelled: () -> Unit = {},
    ): ProjectConsolidation {
        checkCancelled()
        require(source is FileRef.Direct && destination is FileRef.Direct || source is FileRef.Saf && destination is FileRef.Saf) {
            "Choose two concrete project homes using the same storage access mode."
        }
        val sourceKey = source.rawValue()
        val destinationKey = destination.rawValue()
        require(sourceKey != destinationKey) { "Choose separate, non-nested project homes." }
        if (source is FileRef.Direct && destination is FileRef.Direct) {
            val from = sourceKey.trimEnd('/'); val to = destinationKey.trimEnd('/')
            require(from != to && !from.startsWith("$to/") && !to.startsWith("$from/")) { "Choose separate, non-nested project homes." }
        }
        require(sources.size <= 100_000 && destinations.size <= 100_000) { "Project inventory exceeds the bounded consolidation limit." }
        require(sources.distinctBy { it.stableRef }.size == sources.size && destinations.distinctBy { it.stableRef }.size == destinations.size) {
            "Project inventory repeats a source identity. Refresh both homes."
        }
        require(sources.none { it.stableRef == destinationKey } && destinations.none { it.stableRef == sourceKey }) {
            "Choose separate, non-nested project homes."
        }
        val sourceChildren = sources.filter { it.stableRef != sourceKey }.groupBy { it.parentRef }
        val destinationChildren = destinations.filter { it.stableRef != destinationKey }.groupBy { it.parentRef }
        fun reachable(root: String, children: Map<String?, List<FileRecord>>): Set<String> {
            val queue = ArrayDeque<String>(); queue += root
            val seen = hashSetOf(root)
            while (queue.isNotEmpty()) {
                checkCancelled()
                for (row in children[queue.removeFirst()].orEmpty()) {
                    require(seen.add(row.stableRef)) { "Project inventory has cyclic or repeated ancestry. Refresh both homes." }
                    if (row.isDirectory) queue += row.stableRef
                }
            }
            return seen
        }
        val sourceMembers = reachable(sourceKey, sourceChildren)
        val destinationMembers = reachable(destinationKey, destinationChildren)
        require(sources.all { it.stableRef in sourceMembers } && destinations.all { it.stableRef in destinationMembers }) {
            "Project inventory contains entries with unverifiable parent ancestry. Refresh both homes."
        }
        require(sourceMembers.intersect(destinationMembers).isEmpty()) { "Choose separate, non-nested project homes." }

        val rows = (sources + destinations).associateBy { it.stableRef }
        val allChildren = (sources + destinations).groupBy { it.parentRef }
        val markerParents = rows.values.filter { !it.isDirectory && it.displayName == DirectProtection.MARKER }.mapNotNull { it.parentRef }.toSet()
        val protectedSubtrees = hashSetOf<String>()
        val containingMarker = hashSetOf<String>()
        for (parent in markerParents) {
            checkCancelled()
            var ancestor: String? = parent
            while (ancestor != null && containingMarker.add(ancestor)) ancestor = rows[ancestor]?.parentRef
            val queue = ArrayDeque<String>(); queue += parent
            while (queue.isNotEmpty()) {
                checkCancelled()
                val ref = queue.removeFirst()
                if (!protectedSubtrees.add(ref)) continue
                allChildren[ref].orEmpty().forEach { queue += it.stableRef }
            }
        }
        fun collisionKey(parent: String, name: String) = parent to name.lowercase(Locale.ROOT)
        val existingByName = destinations.groupBy { collisionKey(it.parentRef.orEmpty(), it.displayName) }
        val queue = ArrayDeque<Pair<FileRef, FileRef>>(); queue += source to destination
        val visited = hashSetOf<String>()
        val moves = mutableListOf<PlannedOperation.Move>()
        val held = mutableListOf<FileRecord>()
        val reasons = linkedMapOf<String, String>()
        fun hold(record: FileRecord, reason: String) { held += record; reasons[record.stableRef] = reason }
        while (queue.isNotEmpty()) {
            checkCancelled()
            val (from, to) = queue.removeFirst()
            require(visited.add(from.rawValue())) { "Repeated source folder identity." }
            val siblings = sourceChildren[from.rawValue()].orEmpty()
            val sourceNames = siblings.groupingBy { it.displayName.lowercase(Locale.ROOT) }.eachCount()
            for (record in siblings) {
                checkCancelled()
                val existing = existingByName[collisionKey(to.rawValue(), record.displayName)].orEmpty()
                when {
                    record.displayName.isBlank() || record.displayName in setOf(".", "..") || record.displayName.any { it == '/' || it == '\\' || it.isISOControl() } ->
                        hold(record, "Unsafe provider/display name; the original stays in place.")
                    record.stableRef in protectedSubtrees || record.isDirectory && record.stableRef in containingMarker || to.rawValue() in protectedSubtrees ->
                        hold(record, "A no-sort marker protects this source or destination subtree.")
                    sourceNames.getValue(record.displayName.lowercase(Locale.ROOT)) > 1 || existing.size > 1 ->
                        hold(record, "Multiple case-equivalent names require an explicit choice.")
                    existing.isEmpty() -> moves += PlannedOperation.Move(parseFileRef(record.stableRef), to.child(record.displayName),
                        "Consolidate into the project home you chose; preserve this file or intact folder.")
                    record.isDirectory && existing.single().isDirectory -> queue += parseFileRef(record.stableRef) to parseFileRef(existing.single().stableRef)
                    else -> hold(record, "The chosen home already contains this name; no file is overwritten.")
                }
            }
        }
        return ProjectConsolidation(moves, held, reasons)
    }
}
