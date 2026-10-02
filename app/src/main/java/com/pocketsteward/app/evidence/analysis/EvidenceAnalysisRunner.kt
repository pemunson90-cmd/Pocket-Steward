package com.pocketsteward.app.evidence.analysis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Sequential admission bounds expensive work to one file and checkpoints every outcome. */
class EvidenceAnalysisRunner(private val store: EvidenceAnalysisStore) {
    suspend fun run(
        request: EvidenceAnalysisRequest,
        allowed: suspend () -> Boolean,
        inspect: suspend (EvidenceAnalysisSource) -> EvidenceAnalysisOutcome,
        onProgress: (EvidenceAnalysisProgress) -> Unit = {},
    ): EvidenceAnalysisProgress {
        request.validate()
        var progress = store.progress(request.id).also { it.validate(request) }
        if (progress.status == EvidenceAnalysisStatus.COMPLETED || progress.pauseRequested) return progress
        try {
            progress = store.update(request.id) { it.copy(status = EvidenceAnalysisStatus.RUNNING, detail = "Analyzing locally.") }
            onProgress(progress)
            while (progress.processed < request.sources.size) {
                currentCoroutineContext().ensureActive()
                progress = store.progress(request.id).also { it.validate(request) }
                if (progress.pauseRequested || !allowed()) {
                    progress = store.update(request.id) { it.copy(status = EvidenceAnalysisStatus.PAUSED, detail = if (it.pauseRequested) it.detail else "Paused. Restore access and privacy settings if needed, then resume.") }
                    onProgress(progress)
                    return progress
                }
                val source = request.sources[progress.processed]
                onProgress(progress.copy(detail = source.record.displayName))
                val outcome = inspect(source)
                currentCoroutineContext().ensureActive()
                progress = store.update(request.id) { current ->
                    require(current.processed == progress.processed) { "Another runner changed this analysis cursor." }
                    current.after(outcome)
                }
                onProgress(progress)
            }
            progress = store.update(request.id) { it.copy(status = EvidenceAnalysisStatus.COMPLETED, detail = "Analysis complete. Rebuild the review to use the saved evidence.") }
            onProgress(progress)
            return progress
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                progress = store.update(request.id) { it.copy(status = EvidenceAnalysisStatus.PAUSED, detail = if (it.pauseRequested) "Paused by you. Progress is saved." else "Interrupted. Saved progress can resume.") }
                onProgress(progress)
            }
            throw cancelled
        } catch (failure: Exception) {
            progress = store.update(request.id) { it.copy(status = EvidenceAnalysisStatus.FAILED, detail = "Analysis stopped. Saved progress can resume.") }
            onProgress(progress)
            throw failure
        }
    }
}
