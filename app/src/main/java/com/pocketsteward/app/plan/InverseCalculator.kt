package com.pocketsteward.app.plan

import com.pocketsteward.app.data.db.MutationOperationType
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.parseFileRef

/** What undoing one committed [com.pocketsteward.app.data.db.MutationRecord] actually does. */
sealed interface InverseAction {
    data class MoveBack(val from: FileRef, val to: FileRef) : InverseAction
    data class RemoveDirectoryIfEmpty(val directory: FileRef) : InverseAction
    /** Nothing to reverse — e.g. a CreateDirectory that found the folder already there (Section 12's idempotent-create case). */
    data object NothingToUndo : InverseAction
}

/**
 * Plan Section 15's own worked examples, made mechanical:
 *
 * ```
 * MOVE A -> B            undo = MOVE B -> A
 * RENAME A -> C          undo = RENAME C -> A
 * CREATE DIRECTORY X     undo = REMOVE X only if empty and created by this task
 * ```
 *
 * Deliberately pure — no Room, no Android — because a wrong inverse is
 * exactly the kind of mistake that shouldn't first surface on a real
 * device. It's verified the same way as [PlanValidator]: pulled into a
 * throwaway plain-Kotlin module and run against a real compiler and real
 * tests before ever touching [com.pocketsteward.app.executor.PlanExecutor].
 *
 * A `MutationRecord`'s own `sourceBefore`/`destinationAfter` already carry
 * everything needed for MOVE/RENAME/TRASH — the "current location" and
 * "location to restore" are just the two sides of the forward move,
 * swapped. `CREATE_DIRECTORY` is the one case that needs help: nothing
 * about a successful create says whether it actually made a new directory
 * or found one that was already there (Section 12 treats "already exists as
 * a directory" as a harmless accepted no-op, not a failure) — [undoState]
 * carries that distinction via [CREATED_MARKER], written by
 * [com.pocketsteward.app.executor.PlanExecutor] at execution time, since
 * it's the only place that still knows which case happened.
 */
object InverseCalculator {
    const val CREATED_MARKER = "CREATED"

    fun compute(
        operationType: MutationOperationType,
        sourceBefore: String,
        destinationAfter: String?,
        undoState: String?,
    ): InverseAction = when (operationType) {
        MutationOperationType.MOVE, MutationOperationType.RENAME, MutationOperationType.TRASH -> {
            val currentLocation = destinationAfter
            if (currentLocation == null) {
                InverseAction.NothingToUndo
            } else {
                InverseAction.MoveBack(from = parseFileRef(currentLocation), to = parseFileRef(sourceBefore))
            }
        }
        MutationOperationType.CREATE_DIRECTORY -> {
            val createdPath = destinationAfter
            if (undoState != CREATED_MARKER || createdPath == null) {
                InverseAction.NothingToUndo
            } else {
                InverseAction.RemoveDirectoryIfEmpty(parseFileRef(createdPath))
            }
        }
        // Section 2 lists "copy if useful" but nothing in the app plans a
        // COPY yet (Milestone 2's executor only ever emits the other four
        // types) — no inverse defined until something actually produces one.
        MutationOperationType.COPY -> InverseAction.NothingToUndo
    }
}
