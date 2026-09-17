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
}
