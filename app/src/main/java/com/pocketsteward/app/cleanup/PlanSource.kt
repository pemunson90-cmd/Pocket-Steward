package com.pocketsteward.app.cleanup

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.storage.FileRef

/** Everything any plan source gets to see. Nothing here is a mutation primitive. */
data class PlanRequest(
    val scopeRoot: FileRef,
    val records: List<FileRecord>,
    val projectKeywords: List<ProjectKeyword> = emptyList(),
    /**
     * Opt-in, per run, to sorting files that are already inside a subfolder.
     * Default false is Milestone 6's depth guard (spec 6a), not a preference.
     */
    val includeSubfolders: Boolean = false,
)

/**
 * The one way a plan enters this app, whatever produced it.
 *
 * Today there is exactly one implementation, [RuleBasedPlanSource], wrapping
 * Milestone 4's deterministic rule engine. A later on-device model becomes a
 * *second implementation of this interface*, not a second code path — which is
 * the whole point of defining it now, while there is only one.
 *
 * What a [PlanSource] can do is bounded by its return type: it proposes a
 * [GeneratedCleanup], which is an [com.pocketsteward.app.plan.AgentPlan] — a
 * list of typed [com.pocketsteward.app.plan.PlannedOperation]s — plus counts
 * describing what it spared. It never touches a
 * [com.pocketsteward.app.storage.StorageGateway], never sees a mutation
 * primitive, and cannot execute anything it proposes. Everything a source
 * returns still goes through `PlanValidator`, then the preview screen, then
 * `PlanExecutor` and the journal — in that order, with no way around it,
 * because proposing and executing are different types handled by different
 * objects. That ordering is plan Decision 1 and it is not negotiable for an
 * AI source later.
 *
 * The [CleanupScopeReport] rides along rather than being recomputed by the UI
 * on purpose: the preview has to state what a run declined to touch, and the
 * only thing that knows is whatever produced the plan.
 */
interface PlanSource {
    /** Stable identifier recorded with the task, so history says what proposed a plan. */
    val id: String

    suspend fun proposePlan(request: PlanRequest): GeneratedCleanup
}

/** Milestone 4's rule engine, as a [PlanSource]. The only implementation so far. */
object RuleBasedPlanSource : PlanSource {
    override val id: String = "rules"

    override suspend fun proposePlan(request: PlanRequest): GeneratedCleanup =
        CleanupPlanGenerator.generate(
            scopeRoot = request.scopeRoot,
            records = request.records,
            projectKeywords = request.projectKeywords,
            includeSubfolders = request.includeSubfolders,
        )
}
