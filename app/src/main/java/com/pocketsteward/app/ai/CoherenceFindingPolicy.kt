package com.pocketsteward.app.ai

/** Model output must neither duplicate lazy-list identities nor escape its input batch. */
object CoherenceFindingPolicy {
    fun forBatch(findings: List<CoherenceFinding>, allowedIds: Set<String>): List<CoherenceFinding> = findings
        .filter { it.id in allowedIds }.groupBy { it.id }.map { (id, group) ->
            val unique = group.distinct()
            if (unique.size == 1) unique.single() else CoherenceFinding(id, CoherenceClass.UNCERTAIN, "Conflicting model findings; manual review is needed.", null)
        }
}
