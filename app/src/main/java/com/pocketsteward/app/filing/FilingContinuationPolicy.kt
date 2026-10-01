package com.pocketsteward.app.filing

/** Continuation reuses choices, never filesystem authority or stale evidence. */
object FilingContinuationPolicy {
    fun applyAssignments(fresh: InboxFilingResult, assignments: Map<String, FilingDecision>): InboxFilingResult =
        InboxFilingResult(fresh.decisions.map { decision ->
            val assigned = assignments[decision.artifact.stableRef] ?: return@map decision
            // Preserve the chosen placement but attach only fresh automatic evidence.
            assigned.copy(artifact = decision.artifact,
                evidence = assigned.evidence.filter { it.kind == FilingEvidenceKind.USER_MAPPING } + decision.evidence)
        })

    fun selectedSources(newDefaults: Set<String>, previousSources: Set<String>, previousSelected: Set<String>): Set<String> =
        (newDefaults - previousSources) + previousSelected
}
