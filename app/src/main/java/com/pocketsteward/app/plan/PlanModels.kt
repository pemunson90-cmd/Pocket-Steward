package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef

/**
 * The typed operation manifest an "agent" (hard-coded today, rule-engine in
 * Milestone 4, AI in Milestone 6) proposes (plan Section 11). The model
 * never gets a raw `move_file(path, path)` primitive — it can only ever
 * build one of these, and only [PlanValidator]-accepted operations ever
 * reach [com.pocketsteward.app.executor.PlanExecutor].
 *
 * Each case mirrors one [com.pocketsteward.app.storage.StorageGateway]
 * mutation method exactly, so the executor never has to improvise how to
 * turn a plan step into a gateway call.
 */
sealed interface PlannedOperation {
    val reason: String

    data class CreateDirectory(
        val parent: FileRef,
        val name: String,
        override val reason: String,
    ) : PlannedOperation

    data class Move(
        val source: FileRef,
        val destination: FileRef,
        override val reason: String,
    ) : PlannedOperation

    data class Rename(
        val source: FileRef,
        val newName: String,
        override val reason: String,
    ) : PlannedOperation

    /**
     * Never a permanent delete (plan Decision 6, Section 14) — the executor
     * always turns this into a move under an app-managed Trash root that
     * mirrors the file's original location, never a real deletion. There is
     * deliberately no operation in this plan that empties Trash; that stays
     * a manual, outside-the-app action.
     */
    data class Trash(
        val source: FileRef,
        override val reason: String,
        /**
         * The content hash that justified trashing this copy, where one
         * exists. Journaled into `MutationRecord.sourceFingerprint`, which
         * is what lets a manifest built after the fact group trashed files
         * back into the duplicate sets they came from without re-hashing
         * anything — including after the file itself has moved to Trash.
         */
        val sourceFingerprint: String? = null,
    ) : PlannedOperation

    /**
     * Writes [content] to a new file. Never overwrites: [PlanValidator]
     * rejects it if anything already occupies the destination, and the
     * gateway refuses as well, so this can create a file but can never
     * destroy one.
     *
     * Two things need it. A folder-protection marker
     * ([com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER]) has to become a
     * real file on disk to survive an app reinstall or a destructive schema
     * migration, and an exported task manifest has to outlive the journal it
     * was built from, for the same reason.
     *
     * It is deliberately an operation on this list rather than a direct
     * gateway call from the UI. The M6 spec's item 7 wants an AI's first
     * mutation-adjacent power to be "propose a protection marker", and this
     * is what makes that a one-line addition to a planner instead of a new
     * pathway into the mutation layer: a proposed marker goes through
     * [PlanValidator], the preview screen, the executor and the journal, the
     * same as every move.
     */
    data class WriteTextFile(
        val parent: FileRef,
        val name: String,
        val content: String,
        override val reason: String,
    ) : PlannedOperation
}

data class AgentPlan(
    val goal: String,
    val operations: List<PlannedOperation>,
)

/** Plan Section 13. Classified deterministically from operation type alone — never from a model's self-reported confidence, which is a UI hint at most. */
enum class MutationSafetyClass { GREEN, YELLOW, RED }

fun PlannedOperation.safetyClass(): MutationSafetyClass = when (this) {
    is PlannedOperation.CreateDirectory -> MutationSafetyClass.GREEN
    is PlannedOperation.Move -> MutationSafetyClass.GREEN
    is PlannedOperation.Rename -> MutationSafetyClass.GREEN
    // Section 13 lists trash as Red (individual confirmation) even though
    // it's non-destructive here — removing a file from where the user
    // expects to find it is disruptive enough to warrant that.
    is PlannedOperation.Trash -> MutationSafetyClass.RED
    // Creates a file that did not exist and cannot overwrite one. Nothing
    // the user already has changes, which is the definition of Green here.
    is PlannedOperation.WriteTextFile -> MutationSafetyClass.GREEN
}
