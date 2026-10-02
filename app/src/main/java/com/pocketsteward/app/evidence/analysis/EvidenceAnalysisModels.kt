package com.pocketsteward.app.evidence.analysis

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.plan.SourcePrecondition
import java.util.UUID

/** Immutable source metadata captured when this read-only analysis was requested. */
data class EvidenceAnalysisSource(val record: FileRecord, val sourceRoot: String, val folderUnitRef: String? = null)
data class EvidenceAnalysisFolder(val ref: String, val baseline: SourcePrecondition)
data class EvidenceAnalysisRequest(
    val id: String,
    val mode: StorageAccessMode,
    val grant: String?,
    val images: Boolean,
    val content: Boolean,
    val retryUnavailable: Boolean,
    val sources: List<EvidenceAnalysisSource>,
    val folders: List<EvidenceAnalysisFolder> = emptyList(),
    val automatic: Boolean = false,
    val observedRevision: String? = null,
) {
    fun validate() {
        require(UUID.fromString(id).toString() == id)
        require(images || content)
        require(observedRevision == null || observedRevision.matches(Regex("[a-f0-9-]{36}:[0-9]{1,19}")))
        require(sources.size in 1..100_000)
        require(sources.map { it.record.stableRef }.distinct().size == sources.size)
        require(sources.all { it.sourceRoot.isNotBlank() && it.record.stableRef.isNotBlank() && !it.record.isDirectory && it.record.sizeBytes >= 0 })
        require((mode == StorageAccessMode.SAF) == (grant != null))
        require(folders.size <= 100_000 && folders.distinctBy { it.ref }.size == folders.size)
        require(folders.all { it.ref.isNotBlank() && it.baseline.directoryDigest?.matches(Regex("[a-f0-9]{64}")) == true &&
            it.baseline.directoryEntryCount in 0..100_000 && it.baseline.sizeBytes >= 0 })
        val units = folders.mapTo(hashSetOf()) { it.ref }
        require(sources.all { it.folderUnitRef == null || it.folderUnitRef in units })
    }

    fun sameInventoryAs(other: EvidenceAnalysisRequest): Boolean = mode == other.mode && grant == other.grant &&
        images == other.images && content == other.content && observedRevision == other.observedRevision && folders == other.folders && sources.size == other.sources.size &&
        sources.zip(other.sources).all { (a, b) -> a.sourceRoot == b.sourceRoot && a.folderUnitRef == b.folderUnitRef &&
            a.record.stableRef == b.record.stableRef && a.record.displayName == b.record.displayName && a.record.extension == b.record.extension &&
            a.record.mimeType == b.record.mimeType && a.record.parentRef == b.record.parentRef && a.record.sizeBytes == b.record.sizeBytes &&
            a.record.modifiedAt == b.record.modifiedAt }
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
