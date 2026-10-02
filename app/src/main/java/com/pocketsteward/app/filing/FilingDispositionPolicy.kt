package com.pocketsteward.app.filing

object FilingDispositionPolicy {
    fun defer(result: InboxFilingResult, sourceRefs: Set<String>): InboxFilingResult {
        val known = result.decisions.mapTo(hashSetOf()) { it.artifact.stableRef }
        require(sourceRefs.all { it in known }) { "Defer choices must belong to this review." }
        return InboxFilingResult(result.decisions.map { decision ->
            if (decision.artifact.stableRef !in sourceRefs) decision else decision.copy(
                projectName = null, projectHome = null, release = null, destinationDirectory = null,
                confidence = FilingConfidence.UNRESOLVED, createsProjectHome = false,
                evidence = listOf(FilingEvidence(FilingEvidenceKind.USER_MAPPING,
                    "You chose Uncertain for later review.", 100)) + decision.evidence.filter { it.kind != FilingEvidenceKind.USER_MAPPING },
            )
        })
    }
}
