package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue

/**
 * Selection rules for an already-validated plan preview.
 *
 * Rejected operations never enter this list. The only dependency M8 needs to
 * preserve is generated destination folders and the moves that require them:
 * a move cannot stay selected if its required CreateDirectory is deselected,
 * and selecting that move selects the directory again.
 */
object PlanSelection {
    fun allSelected(operations: List<PlannedOperation>): Set<Int> = operations.indices.toSet()

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
        val operation = operations[index]

        if (selected) {
            next += index
            if (operation is PlannedOperation.Move) {
                val parent = operation.destination.parentRaw()
                operations.forEachIndexed { candidateIndex, candidate ->
                    if (candidate is PlannedOperation.CreateDirectory &&
                        candidate.createdDirectoryRaw() == parent
                    ) {
                        next += candidateIndex
                    }
                }
            }
        } else {
            next -= index
            if (operation is PlannedOperation.CreateDirectory) {
                val directory = operation.createdDirectoryRaw()
                operations.forEachIndexed { candidateIndex, candidate ->
                    if (candidate is PlannedOperation.Move &&
                        candidate.destination.parentRaw() == directory
                    ) {
                        next -= candidateIndex
                    }
                }
            }
        }

        // A generated folder with dependent moves should not remain selected
        // after the final selected move into it is turned off. Standalone
        // CreateDirectory operations are left alone.
        operations.forEachIndexed { createIndex, candidate ->
            if (candidate !is PlannedOperation.CreateDirectory) return@forEachIndexed
            val directory = candidate.createdDirectoryRaw()
            val dependentMoves = operations.withIndex().filter {
                it.value is PlannedOperation.Move &&
                    (it.value as PlannedOperation.Move).destination.parentRaw() == directory
            }
            if (dependentMoves.isNotEmpty() &&
                dependentMoves.none { it.index in next }
            ) {
                next -= createIndex
            }
        }

        return next
    }
}

private fun PlannedOperation.CreateDirectory.createdDirectoryRaw(): String =
    "${parent.rawValue().trimEnd('/')}/$name"

private fun FileRef.parentRaw(): String? {
    val raw = rawValue().trimEnd('/')
    val parent = raw.substringBeforeLast('/', missingDelimiterValue = "")
    return parent.takeIf { it.isNotBlank() }
}
