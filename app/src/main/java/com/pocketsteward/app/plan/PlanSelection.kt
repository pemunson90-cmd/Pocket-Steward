package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.knownParentOrNull
import com.pocketsteward.app.storage.child

/**
 * Selection rules for an already-validated plan preview.
 *
 * M9 extends M8's dependency rule to nested destination trees. Selecting a
 * file operation selects every planned directory it needs. Deselecting a
 * directory removes every selected child operation that depends on it.
 */
object PlanSelection {
    fun allSelected(operations: List<PlannedOperation>): Set<Int> = operations.indices.toSet()

    fun noneSelected(): Set<Int> = emptySet()

    fun safeSelected(operations: List<PlannedOperation>): Set<Int> {
        val directoryIndices = buildMap<String, Int> {
            operations.forEachIndexed { index, operation ->
                if (operation is PlannedOperation.CreateDirectory) putIfAbsent(operation.createdDirectoryRaw(), index)
            }
        }
        val requiredParents = operations.map { operation ->
            when (operation) {
                is PlannedOperation.CreateDirectory -> operation.parent.rawValue()
                is PlannedOperation.Move -> operation.destination.parentRaw()
                is PlannedOperation.Copy -> operation.destination.parentRaw()
                is PlannedOperation.WriteTextFile -> operation.parent.rawValue()
                else -> null
            }
        }
        val dependents = requiredParents.mapNotNull { it?.let(directoryIndices::get) }.toSet()
        val selected = linkedSetOf<Int>()
        fun addWithParents(index: Int) {
            var current: Int? = index
            while (current != null && selected.add(current)) current = requiredParents[current]?.let(directoryIndices::get)
        }
        // Standalone requested directories remain selectable. Generated
        // directories with dependents are added only when a safe dependent needs them.
        operations.forEachIndexed { index, operation ->
            if (operation.safetyClass() == MutationSafetyClass.GREEN &&
                (operation !is PlannedOperation.CreateDirectory || index !in dependents)) addWithParents(index)
        }
        return selected
    }

    fun selectedOperations(
        operations: List<PlannedOperation>,
        selectedIndices: Set<Int>,
    ): List<PlannedOperation> =
        operations.filterIndexed { index, _ -> index in selectedIndices }

    /** Reuse this index while the operation-list identity remains unchanged. */
    class Dependencies internal constructor(internal val operations: List<PlannedOperation>) {
        internal val parent = IntArray(operations.size) { -1 }
        internal val children = Array(operations.size) { mutableListOf<Int>() }
        init {
            val directories = buildMap<String, Int> {
                operations.forEachIndexed { index, op ->
                    if (op is PlannedOperation.CreateDirectory) putIfAbsent(op.createdDirectoryRaw(), index)
                }
            }
            operations.forEachIndexed { index, op ->
                val required = when (op) {
                    is PlannedOperation.CreateDirectory -> op.parent.rawValue()
                    is PlannedOperation.Move -> op.destination.parentRaw()
                    is PlannedOperation.Copy -> op.destination.parentRaw()
                    is PlannedOperation.WriteTextFile -> op.parent.rawValue()
                    else -> null
                }
                val ancestor = required?.let(directories::get) ?: -1
                if (ancestor >= 0 && ancestor != index) {
                    parent[index] = ancestor
                    children[ancestor].add(index)
                }
            }
        }
        fun matches(candidate: List<PlannedOperation>): Boolean = operations === candidate

        fun includeParents(indices: Set<Int>): Set<Int> {
            val selected = linkedSetOf<Int>()
            for (index in indices) {
                var current = index
                while (current in operations.indices && selected.add(current)) current = parent[current]
            }
            return selected
        }
    }

    fun dependencies(operations: List<PlannedOperation>): Dependencies = Dependencies(operations)

    fun setSelected(
        operations: List<PlannedOperation>, current: Set<Int>, index: Int, selected: Boolean,
        dependencies: Dependencies = dependencies(operations),
    ): Set<Int> {
        if (index !in operations.indices) return current
        require(dependencies.matches(operations)) { "Selection dependencies belong to another plan." }
        val next = current.filterTo(linkedSetOf()) { it in operations.indices }
        val counts = IntArray(operations.size)
        next.forEach { child -> dependencies.parent[child].takeIf { it >= 0 }?.let { counts[it]++ } }
        fun add(item: Int): Boolean {
            if (!next.add(item)) return false
            dependencies.parent[item].takeIf { it >= 0 }?.let { counts[it]++ }
            return true
        }
        fun remove(item: Int): Boolean {
            if (!next.remove(item)) return false
            dependencies.parent[item].takeIf { it >= 0 }?.let { counts[it]-- }
            return true
        }
        if (selected) {
            var item = index
            while (item >= 0 && add(item)) item = dependencies.parent[item]
        } else {
            val pending = ArrayDeque<Int>(); pending.add(index)
            while (pending.isNotEmpty()) {
                val item = pending.removeFirst()
                if (remove(item)) dependencies.children[item].forEach(pending::addLast)
            }
        }
        // Prune unused generated directories in one queue; each edge is inspected at most once.
        val unused = ArrayDeque<Int>()
        operations.indices.filterTo(unused) { it in next && dependencies.children[it].isNotEmpty() && counts[it] == 0 }
        while (unused.isNotEmpty()) {
            val item = unused.removeFirst()
            if (counts[item] != 0 || !remove(item)) continue
            val ancestor = dependencies.parent[item]
            if (ancestor >= 0 && counts[ancestor] == 0) unused.addLast(ancestor)
        }
        return next
    }

}

private fun PlannedOperation.CreateDirectory.createdDirectoryRaw(): String =
    parent.child(name).rawValue()

private fun FileRef.parentRaw(): String? =
    knownParentOrNull()?.rawValue()
