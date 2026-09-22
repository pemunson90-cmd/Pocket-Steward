package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.knownParentOrNull

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
        var selected = emptySet<Int>()
        operations.forEachIndexed { index, operation ->
            if (operation.safetyClass() != MutationSafetyClass.RED) {
                selected = setSelected(
                    operations = operations,
                    current = selected,
                    index = index,
                    selected = true,
                )
            }
        }
        return selected
    }

    fun selectedOperations(
        operations: List<PlannedOperation>,
        selectedIndices: Set<Int>,
    ): List<PlannedOperation> =
        operations.filterIndexed { index, _ -> index in selectedIndices }

    fun setSelected(
        operations: List<PlannedOperation>,
        current: Set<Int>,
        index: Int,
        selected: Boolean,
    ): Set<Int> {
        if (index !in operations.indices) return current

        val next = current.toMutableSet()
        if (selected) {
            selectWithDependencies(operations, next, index)
        } else {
            deselectWithDependents(operations, next, index)
        }

        // Prune generated directories whose plan-time dependents have all
        // been turned off. Repeat because removing a child directory can make
        // its parent unused too.
        var changed: Boolean
        do {
            changed = false
            operations.forEachIndexed { createIndex, candidate ->
                if (candidate !is PlannedOperation.CreateDirectory || createIndex !in next) return@forEachIndexed
                val dependents = dependentIndices(operations, createIndex)
                if (dependents.isNotEmpty() && dependents.none { it in next }) {
                    next -= createIndex
                    changed = true
                }
            }
        } while (changed)

        return next
    }

    private fun selectWithDependencies(
        operations: List<PlannedOperation>,
        selected: MutableSet<Int>,
        index: Int,
    ) {
        if (!selected.add(index)) return
        requiredCreateIndex(operations, operations[index])?.let { dependency ->
            selectWithDependencies(operations, selected, dependency)
        }
    }

    private fun deselectWithDependents(
        operations: List<PlannedOperation>,
        selected: MutableSet<Int>,
        index: Int,
    ) {
        if (!selected.remove(index)) return
        if (operations[index] !is PlannedOperation.CreateDirectory) return
        dependentIndices(operations, index).forEach { dependent ->
            deselectWithDependents(operations, selected, dependent)
        }
    }

    private fun requiredCreateIndex(
        operations: List<PlannedOperation>,
        operation: PlannedOperation,
    ): Int? {
        val requiredParent = when (operation) {
            is PlannedOperation.CreateDirectory -> operation.parent.rawValue()
            is PlannedOperation.Move -> operation.destination.parentRaw()
            is PlannedOperation.Copy -> operation.destination.parentRaw()
            is PlannedOperation.WriteTextFile -> operation.parent.rawValue()
            is PlannedOperation.Rename,
            is PlannedOperation.Trash,
            -> null
        } ?: return null

        return operations.indexOfFirst { candidate ->
            candidate is PlannedOperation.CreateDirectory &&
                candidate.createdDirectoryRaw() == requiredParent
        }.takeIf { it >= 0 }
    }

    private fun dependentIndices(
        operations: List<PlannedOperation>,
        createIndex: Int,
    ): List<Int> {
        val create = operations.getOrNull(createIndex) as? PlannedOperation.CreateDirectory ?: return emptyList()
        val directory = create.createdDirectoryRaw()
        return operations.indices.filter { candidateIndex ->
            if (candidateIndex == createIndex) return@filter false
            when (val candidate = operations[candidateIndex]) {
                is PlannedOperation.CreateDirectory -> candidate.parent.rawValue() == directory
                is PlannedOperation.Move -> candidate.destination.parentRaw() == directory
                is PlannedOperation.Copy -> candidate.destination.parentRaw() == directory
                is PlannedOperation.WriteTextFile -> candidate.parent.rawValue() == directory
                is PlannedOperation.Rename,
                is PlannedOperation.Trash,
                -> false
            }
        }
    }
}

private fun PlannedOperation.CreateDirectory.createdDirectoryRaw(): String =
    "${parent.rawValue().trimEnd('/')}/$name"

private fun FileRef.parentRaw(): String? =
    knownParentOrNull()?.rawValue()
