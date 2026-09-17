package com.pocketsteward.app.cleanup

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.storage.FileRef

/** Everything any plan source gets to see. Nothing here is a mutation primitive. */
data class PlanRequest(
    val scopeRoot: FileRef,
    val records: List<FileRecord>,
    val projectKeywords: List<ProjectKeyword> = emptyList(),
)

/**
 * The one way a plan enters this app, whatever produced it.
 *
 * Today there is exactly one implementation, [RuleBasedPlanSource], wrapping
 * Milestone 4's deterministic rule engine. Milestone 6's on-device model
 * becomes a *second implementation of this interface*, not a second code path
 * — which is the whole point of defining it now, while there is only one.
 *
 * What a [PlanSource] can do is bounded by its return type: it proposes an
 * [AgentPlan], a list of typed [com.pocketsteward.app.plan.PlannedOperation]s
 * and nothing else. It never touches a [com.pocketsteward.app.storage.StorageGateway],
 * never sees a mutation primitive, and cannot execute anything it proposes.
 * Everything a source returns still goes through `PlanValidator`, then the
 * preview screen, then `PlanExecutor` and the journal — in that order, with
 * no way around it, because proposing and executing are different types
 * handled by different objects. That ordering is plan Decision 1 and it is
 * not negotiable for an AI source later.
 */
interface PlanSource {
    /** Stable identifier recorded with the task, so history says what proposed a plan. */
    val id: String

    suspend fun proposePlan(request: PlanRequest): AgentPlan
}

/** Milestone 4's rule engine, as a [PlanSource]. The only implementation until Milestone 6. */
object RuleBasedPlanSource : PlanSource {
    override val id: String = "rules"

    override suspend fun proposePlan(request: PlanRequest): AgentPlan =
        CleanupPlanGenerator.generate(request.scopeRoot, request.records, request.projectKeywords)
}
