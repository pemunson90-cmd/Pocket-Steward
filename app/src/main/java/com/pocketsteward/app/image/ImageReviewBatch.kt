package com.pocketsteward.app.image

import com.pocketsteward.app.data.db.FileRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

data class ImageReviewBudget(val freshImages: Int = 200, val freshOcr: Int = 40) {
    init { require(freshImages >= 0 && freshOcr >= 0) }
}

data class ImageReviewCoverage(
    val total: Int, val cached: Int, val freshAttempts: Int, val ocrAttempts: Int,
    val deferredImages: Int, val deferredOcr: Int, val unavailable: Int, val unavailableOcr: Int,
    val progressNotSaved: Int = 0, val enabled: Boolean = true, val textEnabled: Boolean = false,
) {
    val hasDeferred: Boolean get() = deferredImages > 0 || deferredOcr > 0
    val summary: String get() = if (!enabled) "Image analysis is off; no image pixels or text were inspected." else buildString {
        append("Local image evidence: $total candidates, $cached cached, $freshAttempts fresh attempts, $unavailable unavailable. ")
        append("$deferredImages images deferred by this review's analysis budget. ")
        if (textEnabled) append("Image OCR: $ocrAttempts fresh attempts, $deferredOcr deferred, $unavailableOcr unavailable. ")
        else append("Image text inspection is off. ")
        if (hasDeferred) append("Continue image evidence to inspect the next batch while retaining assignments and selections. ")
        if (progressNotSaved > 0) append("Progress for $progressNotSaved attempts could not be saved; check free storage.")
    }.trim()
}

data class ImageReviewResult(val evidence: Map<String, ImageInsight>, val coverage: ImageReviewCoverage)
enum class ImageReviewPhase { CHECKING_CACHE, INSPECTING }

interface ImageEvidenceSource {
    suspend fun cached(record: FileRecord, inspectText: Boolean): ImageInsight?
    suspend fun attemptedAt(record: FileRecord, inspectText: Boolean): Long?
    suspend fun unavailable(record: FileRecord, inspectText: Boolean): Boolean
    suspend fun noteOutcome(record: FileRecord, inspectText: Boolean, insight: ImageInsight?): Boolean
    suspend fun markAttempt(record: FileRecord, inspectText: Boolean): Boolean
    suspend fun analyze(record: FileRecord, inspectText: Boolean = false, allowFresh: Boolean = true): ImageInsight?
}

/** Read-only evidence admission. Cached results never consume fresh decode/OCR allowances. */
class ImageReviewBatch(private val source: ImageEvidenceSource, private val budget: ImageReviewBudget = ImageReviewBudget()) {
    suspend fun inspect(records: List<FileRecord>, inspectText: Boolean, retryUnavailable: Boolean = false,
        onProgress: (ImageReviewPhase, Int, Int, String) -> Unit = { _, _, _, _ -> }): ImageReviewResult {
        val candidates = records.filter { !it.isDirectory && it.extension.lowercase(Locale.ROOT) in EXTENSIONS }.distinctBy { it.stableRef }
        val evidence = linkedMapOf<String, ImageInsight>()
        data class Pending(val record: FileRecord, val visual: ImageInsight?, val attempt: Long?)
        val pending = mutableListOf<Pending>()
        var cached = 0; var unavailable = 0; var unavailableOcr = 0
        for ((index, record) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val full = safely { source.cached(record, inspectText) }?.takeIf { !inspectText || it.textInspectionComplete }
            if (full != null) { evidence[record.stableRef] = full; cached++ }
            else {
                val visual = if (inspectText) safely { source.cached(record, false) } else null
                if (visual != null) { evidence[record.stableRef] = visual; cached++ }
                val visualFailed = visual == null && !retryUnavailable && safely { source.unavailable(record, false) } == true
                val textFailed = inspectText && !retryUnavailable && safely { source.unavailable(record, true) } == true
                if (visualFailed) { unavailable++; if (inspectText) unavailableOcr++ }
                else if (visual != null && textFailed) unavailableOcr++
                else {
                    val requestedAttempt = safely { source.attemptedAt(record, inspectText) }
                    val attempt = requestedAttempt ?: if (inspectText && visual == null) safely { source.attemptedAt(record, false) } else null
                    pending += Pending(record, visual, attempt)
                }
            }
            if (index % 25 == 0 || index == candidates.lastIndex)
                onProgress(ImageReviewPhase.CHECKING_CACHE, index + 1, candidates.size, record.displayName)
        }
        var fresh = 0; var ocr = 0; var deferredImages = 0; var deferredOcr = 0
        var unsaved = 0
        // Unseen candidates precede older attempts, so persistent failures cannot starve new evidence.
        val ordered = pending.sortedWith(compareBy<Pending> { it.attempt ?: Long.MIN_VALUE }.thenBy { it.record.stableRef })
        for ((index, item) in ordered.withIndex()) {
            currentCoroutineContext().ensureActive()
            val needsOcr = inspectText
            val permitOcr = needsOcr && ocr < budget.freshOcr && fresh < budget.freshImages
            if (fresh >= budget.freshImages || (item.visual != null && !permitOcr)) {
                if (item.visual == null) deferredImages++
                if (needsOcr) deferredOcr++
                continue
            }
            if (needsOcr && !permitOcr) deferredOcr++
            fresh++
            if (permitOcr) ocr++
            val startedSaved = safely { source.markAttempt(item.record, permitOcr) } == true
            currentCoroutineContext().ensureActive()
            val insight = safely { source.analyze(item.record, inspectText = permitOcr, allowFresh = true) }
            currentCoroutineContext().ensureActive()
            val outcomeSaved = safely { source.noteOutcome(item.record, permitOcr, insight) } == true
            if (!startedSaved || !outcomeSaved) unsaved++
            if (insight == null) {
                evidence.remove(item.record.stableRef)
                if (item.visual == null) unavailable++
                if (permitOcr) unavailableOcr++
            } else {
                evidence[item.record.stableRef] = insight
                if (permitOcr && !insight.textInspectionComplete) unavailableOcr++
            }
            onProgress(ImageReviewPhase.INSPECTING, index + 1, ordered.size, item.record.displayName)
        }
        currentCoroutineContext().ensureActive()
        return ImageReviewResult(evidence, ImageReviewCoverage(candidates.size, cached, fresh, ocr,
            deferredImages, deferredOcr, unavailable, unavailableOcr, unsaved, textEnabled = inspectText))
    }

    private suspend fun <T> safely(action: suspend () -> T): T? = try {
        val value = action()
        currentCoroutineContext().ensureActive()
        value
    }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }

    companion object { val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif") }
}
