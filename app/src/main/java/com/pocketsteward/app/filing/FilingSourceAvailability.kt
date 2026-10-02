package com.pocketsteward.app.filing

/** Access failures cannot become a checkpoint move, including after applying saved assignments. */
object FilingSourceAvailability {
    fun block(result: InboxFilingResult, failures: Map<String, String>): InboxFilingResult =
        InboxFilingResult(result.decisions.map { decision ->
            val reason = failures[decision.artifact.stableRef] ?: return@map decision
            decision.copy(projectName = null, projectHome = null, release = null, destinationDirectory = null,
                confidence = FilingConfidence.UNRESOLVED, createsProjectHome = false,
                evidence = listOf(FilingEvidence(FilingEvidenceKind.SOURCE_UNAVAILABLE, reason, 0)))
        })
}
