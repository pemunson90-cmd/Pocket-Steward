package com.pocketsteward.app.evidence.analysis

import com.pocketsteward.app.plan.SourcePrecondition
import com.pocketsteward.app.plan.SourcePreconditions
import kotlinx.coroutines.CancellationException

/** One structural check per intact folder per worker slice; individual reads still check live identity. */
class EvidenceAnalysisFolderGuard(
    folders: List<EvidenceAnalysisFolder>,
    private val capture: suspend (String) -> SourcePrecondition,
) {
    private val expected = folders.associateBy { it.ref }
    private val checked = hashMapOf<String, EvidenceAnalysisOutcome?>()
    suspend fun refusal(source: EvidenceAnalysisSource): EvidenceAnalysisOutcome? {
        val ref = source.folderUnitRef ?: return null
        if (checked.containsKey(ref)) return checked[ref]
        val baseline = expected[ref]?.baseline ?: return EvidenceAnalysisOutcome.UNAVAILABLE
        val result = try {
            if (SourcePreconditions.matches(baseline, capture(ref))) null else EvidenceAnalysisOutcome.CHANGED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { EvidenceAnalysisOutcome.UNAVAILABLE }
        checked[ref] = result
        return result
    }
}
