package com.pocketsteward.app.evidence.analysis

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageAccessMode
import java.util.UUID

/** Immutable source metadata captured when this read-only analysis was requested. */
data class EvidenceAnalysisSource(val record: FileRecord, val sourceRoot: String)
data class EvidenceAnalysisRequest(
    val id: String,
    val mode: StorageAccessMode,
    val grant: String?,
    val images: Boolean,
    val content: Boolean,
    val retryUnavailable: Boolean,
    val sources: List<EvidenceAnalysisSource>,
) {
    fun validate() {
        require(UUID.fromString(id).toString() == id)
        require(images || content)
        require(sources.size in 1..100_000)
        require(sources.map { it.record.stableRef }.distinct().size == sources.size)
        require(sources.all { it.sourceRoot.isNotBlank() && it.record.stableRef.isNotBlank() && !it.record.isDirectory && it.record.sizeBytes >= 0 })
        require((mode == StorageAccessMode.SAF) == (grant != null))
    }
}
enum class EvidenceAnalysisStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }
enum class EvidenceAnalysisOutcome { ANALYZED, REUSED, PARTIAL, UNAVAILABLE, CHANGED }
data class EvidenceAnalysisProgress(
    val id: String,
    val status: EvidenceAnalysisStatus,
    val total: Int,
    val processed: Int = 0,
    val analyzed: Int = 0,
    val reused: Int = 0,
    val partial: Int = 0,
    val unavailable: Int = 0,
    val changed: Int = 0,
    val pauseRequested: Boolean = false,
    val detail: String = "Waiting to analyze locally.",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun validate(request: EvidenceAnalysisRequest) {
        require(id == request.id && total == request.sources.size && processed in 0..total)
        require(listOf(analyzed, reused, partial, unavailable, changed).all { it >= 0 })
        require(analyzed.toLong() + reused + partial + unavailable + changed == processed.toLong())
    }
    val summary: String get() = "$processed of $total files · $analyzed analyzed · $reused reused · $partial partial · $unavailable unavailable · $changed changed"
    fun after(outcome: EvidenceAnalysisOutcome): EvidenceAnalysisProgress = copy(
        processed = processed + 1,
        analyzed = analyzed + if (outcome == EvidenceAnalysisOutcome.ANALYZED) 1 else 0,
        reused = reused + if (outcome == EvidenceAnalysisOutcome.REUSED) 1 else 0,
        partial = partial + if (outcome == EvidenceAnalysisOutcome.PARTIAL) 1 else 0,
        unavailable = unavailable + if (outcome == EvidenceAnalysisOutcome.UNAVAILABLE) 1 else 0,
        changed = changed + if (outcome == EvidenceAnalysisOutcome.CHANGED) 1 else 0,
        updatedAt = System.currentTimeMillis(),
    )
}
