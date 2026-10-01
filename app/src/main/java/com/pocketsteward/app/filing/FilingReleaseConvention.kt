package com.pocketsteward.app.filing

import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import java.util.Locale

/** Preserve an existing release folder's spelling; competing conventions need review. */
object FilingReleaseConvention {
    fun reconcile(result: InboxFilingResult, existingDirectories: Set<String>): InboxFilingResult {
        val children = existingDirectories.groupBy { it.trimEnd('/').substringBeforeLast('/').lowercase(Locale.ROOT) }
        return InboxFilingResult(result.decisions.map { decision ->
            val home = decision.projectHome ?: return@map decision
            val release = decision.release ?: return@map decision
            if (home.hierarchy != ProjectHierarchyStrategy.VERSIONED || decision.artifact.isDirectory || decision.confidence == FilingConfidence.UNRESOLVED) return@map decision
            val candidates = children[home.path.trimEnd('/').lowercase(Locale.ROOT)].orEmpty()
                .filter { canonical(it.substringAfterLast('/')) == canonical(release) }.distinct()
            when (candidates.size) {
                0 -> decision
                1 -> decision.copy(destinationDirectory = candidates.single(), release = candidates.single().substringAfterLast('/'))
                else -> decision.copy(destinationDirectory = null, confidence = FilingConfidence.UNRESOLVED,
                    evidence = listOf(FilingEvidence(FilingEvidenceKind.DESTINATION_CONFLICT, "Multiple existing folders represent release $release; choose the intended release folder.", 100)) + decision.evidence)
            }
        })
    }

    private fun canonical(value: String): String = value.trim().removePrefix("v").removePrefix("V").lowercase(Locale.ROOT)
}
