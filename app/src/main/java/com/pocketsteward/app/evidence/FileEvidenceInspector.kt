package com.pocketsteward.app.evidence

import com.pocketsteward.app.content.index.IndexedDocument
import com.pocketsteward.app.content.index.IndexedSegment
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.image.ImageInsight
import com.pocketsteward.app.metadata.MetadataEnrichment
import com.pocketsteward.app.saved.CorrectionRule
import com.pocketsteward.app.saved.CorrectionRulePolicy
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EvidenceOrigin { FILE_METADATA, EMBEDDED_METADATA, PACKAGE_METADATA, ARCHIVE_ENTRY, DOCUMENT_TEXT, DOCUMENT_OCR, IMAGE_LABEL, IMAGE_OCR, USER_RULE, REVIEW_REASON, MODEL_ADVICE, COVERAGE }
data class EvidenceSection(val origin: EvidenceOrigin, val title: String, val lines: List<String>, val complete: Boolean? = null)
data class EvidenceReviewContext(val owner: String? = null, val release: String? = null, val destination: String? = null,
    val confidence: String? = null, val reasons: List<String> = emptyList(), val modelAdvice: String? = null)
data class EvidenceExpectedSource(val sizeBytes: Long, val modifiedAt: Long?, val directory: Boolean)
data class EvidenceRequest(val ref: String, val mode: StorageAccessMode? = null, val sourceRoot: String? = null,
    val expected: EvidenceExpectedSource? = null, val review: EvidenceReviewContext? = null)
data class EvidencePrivacy(val metadata: Boolean, val content: Boolean, val images: Boolean)
data class DocumentEvidenceMaterial(val document: IndexedDocument, val segments: List<IndexedSegment>)
data class ImageEvidenceMaterial(val insight: ImageInsight?, val coverage: String)
data class FileEvidenceReport(val name: String, val ref: String, val status: String, val sections: List<EvidenceSection>)

fun FileRecord.evidenceRequest(sourceRoot: String? = null, review: EvidenceReviewContext? = null) = EvidenceRequest(stableRef,
    if (stableRef.startsWith("content://")) StorageAccessMode.SAF else StorageAccessMode.DIRECT, sourceRoot,
    EvidenceExpectedSource(sizeBytes, modifiedAt, isDirectory), review)

/** One bounded read-only inspection. Every consumer gets the same provenance and freshness rules. */
class FileEvidenceInspector(
    private val resolve: suspend (String) -> FileRecord?,
    private val currentMode: suspend () -> StorageAccessMode?,
    private val permitted: suspend (EvidenceRequest, StorageAccessMode) -> Boolean,
    private val refusal: suspend (EvidenceRequest, StorageAccessMode) -> String?,
    private val observe: suspend (String, StorageAccessMode) -> FileMetadata,
    private val fingerprint: suspend (FileRecord, StorageAccessMode) -> String,
    private val privacy: suspend () -> EvidencePrivacy,
    private val metadata: suspend (FileRecord) -> MetadataEnrichment,
    private val document: suspend (FileRecord, StorageAccessMode, String?) -> DocumentEvidenceMaterial,
    private val image: suspend (FileRecord, Boolean) -> ImageEvidenceMaterial,
    private val rules: suspend () -> List<CorrectionRule>,
) {
    private val serial = Mutex()
    suspend fun inspect(request: EvidenceRequest): FileEvidenceReport = serial.withLock {
        val record = resolve(request.ref) ?: return@withLock FileEvidenceReport("File", request.ref, "No current inventory record. Refresh the library before inspecting this source.", emptyList())
        fun report(status: String, sections: List<EvidenceSection> = emptyList()) = FileEvidenceReport(record.displayName, request.ref, status, sections)
        val mode = currentMode() ?: return@withLock report("Storage access is unavailable.")
        if (request.mode != null && request.mode != mode || !permitted(request, mode)) return@withLock report("Storage access changed or this file is outside the active access. Reopen its authorized folder.")
        try {
            val live = observe(request.ref, mode)
            val expected = request.expected ?: EvidenceExpectedSource(record.sizeBytes, record.modifiedAt, record.isDirectory)
            fun matches(value: FileMetadata) = value.displayName == record.displayName && value.sizeBytes == expected.sizeBytes &&
                value.modifiedAtEpochMs == expected.modifiedAt && value.isDirectory == expected.directory
            val facts = EvidenceSection(EvidenceOrigin.FILE_METADATA, "Observed file", listOf(request.ref,
                "${live.sizeBytes} bytes · ${live.mimeType ?: record.extension.ifBlank { "unknown type" }}",
                "Modified: ${live.modifiedAtEpochMs?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "unavailable"}", if (live.isDirectory) "Intact folder" else "File"))
            if (!matches(live)) return@withLock report("Changed since this inventory/review. Refresh and rebuild before using its evidence.", listOf(facts))
            val protected = if (live.isDirectory) null else refusal(request, mode)
            if (protected != null) return@withLock report(protected, listOf(facts))
            val sections = mutableListOf(facts)
            if (live.isDirectory) sections += EvidenceSection(EvidenceOrigin.COVERAGE, "Folder coverage", listOf("This view shows the folder itself. It does not split its contents or certify the saved descendant snapshot; task approval checks that snapshot."), false)
            val enabled = privacy()
            val sampled = if (!live.isDirectory && (enabled.metadata || enabled.content || enabled.images)) fingerprint(record, mode) else null
            suspend fun checkAccess() {
                currentCoroutineContext().ensureActive()
                check(currentMode() == mode && permitted(request, mode)) { "Storage access changed during inspection." }
            }
            if (!live.isDirectory && enabled.metadata && privacy().metadata) {
                checkAccess()
                val meta = metadata(record)
                if (privacy().metadata) {
                    val details = listOfNotNull(meta.record.width?.let { "Width: $it" }, meta.record.height?.let { "Height: $it" },
                        meta.record.durationMs?.let { "Duration ms: $it" }, meta.captureDate?.let { "Capture date: $it" }, meta.exifCamera?.let { "Camera: $it" },
                        meta.mediaArtist?.let { "Artist: $it" }, meta.mediaAlbum?.let { "Album: $it" }, meta.pdfPageCount?.let { "PDF pages: $it" })
                    if (details.isNotEmpty()) sections += EvidenceSection(EvidenceOrigin.EMBEDDED_METADATA, "Embedded metadata", details)
                    val packageDetails = listOfNotNull(meta.apkLabel, meta.record.apkPackageName, meta.record.apkVersionName?.let { "Version: $it" }, meta.apkVersionCode?.let { "Version code: $it" })
                    if (packageDetails.isNotEmpty()) sections += EvidenceSection(EvidenceOrigin.PACKAGE_METADATA, "Observed APK package", packageDetails)
                    if (meta.archiveComplete != null) sections += EvidenceSection(EvidenceOrigin.ARCHIVE_ENTRY, "Observed archive entry names", meta.archiveSample.take(40) + listOfNotNull(meta.archiveNote) + "Observed entries: ${meta.archiveEntryCount ?: 0}. Archive stays intact.", meta.archiveComplete)
                }
            }
            if (!live.isDirectory && enabled.content && privacy().content && com.pocketsteward.app.content.ContentExtractor.supports(record.extension)) {
                checkAccess()
                val text = document(record, mode, request.sourceRoot)
                if (privacy().content) {
                    sections += EvidenceSection(EvidenceOrigin.COVERAGE, "Document inspection coverage", listOf(if (text.document.extractionProfile == "FILING") "Bounded preview inspection" else "Saved full inspection",
                        text.document.extractionStatus.lowercase().replaceFirstChar { it.titlecase() }, text.document.extractionError.orEmpty()), text.document.coverageComplete)
                    text.segments.take(8).forEach { segment -> sections += EvidenceSection(if (segment.ocr) EvidenceOrigin.DOCUMENT_OCR else EvidenceOrigin.DOCUMENT_TEXT,
                        "${if (segment.ocr) "OCR" else "Extracted text"}${segment.pageNumber?.let { " · page $it" }.orEmpty()}", listOf(segment.body.take(1200)), text.document.coverageComplete) }
                }
            }
            if (!live.isDirectory && enabled.images && privacy().images && record.extension.lowercase(java.util.Locale.ROOT) in com.pocketsteward.app.image.ImageReviewBatch.EXTENSIONS) {
                checkAccess()
                val visual = image(record, privacy().content)
                val now = privacy()
                if (now.images) {
                    sections += EvidenceSection(EvidenceOrigin.COVERAGE, "Image inspection coverage", listOf(visual.coverage))
                    visual.insight?.let { insight ->
                        sections += EvidenceSection(EvidenceOrigin.IMAGE_LABEL, "Observed local image labels", insight.labels.take(12).map { "${it.label} · score ${it.confidence}" } + listOfNotNull(insight.screenshotEvidence))
                        if (now.content && insight.textInspectionEnabled) sections += EvidenceSection(EvidenceOrigin.IMAGE_OCR, "Text detected in image", listOf(insight.detectedText.take(4000).ifBlank { if (insight.textInspectionComplete) "No text detected." else "OCR is unavailable or incomplete." }), insight.textInspectionComplete && !insight.textTruncated)
                    }
                }
            }
            checkAccess()
            val closingPrivacy = privacy()
            val needsSample = closingPrivacy.metadata || closingPrivacy.content || closingPrivacy.images
            if (!matches(observe(request.ref, mode)) || sampled != null && needsSample && sampled != fingerprint(record, mode)) return@withLock report("Source changed during inspection. Rebuild the review; derived evidence was discarded.", listOf(facts))
            val matches = CorrectionRulePolicy.matching(rules(), record.stableRef, record.parentRef, record.displayName)
            if (matches.isNotEmpty()) sections += EvidenceSection(EvidenceOrigin.USER_RULE, "Matching remembered ownership rules", matches.take(100).map { "${it.term} → ${it.destinationFolder} · source ${it.sourceFolder ?: "all folders"}${it.projectHomePath?.let { home -> " · home $home" }.orEmpty()}" })
            request.review?.let { context ->
                sections += EvidenceSection(EvidenceOrigin.REVIEW_REASON, "Current review placement", listOfNotNull(context.owner?.let { "Project: $it" }, context.release?.let { "Release: $it" },
                    context.destination?.let { "Destination: $it" }, context.confidence?.let { "Review confidence: $it" }) + context.reasons.take(16))
                context.modelAdvice?.let { sections += EvidenceSection(EvidenceOrigin.MODEL_ADVICE, "Model advice for this review", listOf(it.take(2000))) }
            }
            val finalPrivacy = privacy()
            val visible = sections.filter { section -> when (section.origin) {
                EvidenceOrigin.DOCUMENT_TEXT, EvidenceOrigin.DOCUMENT_OCR -> finalPrivacy.content
                EvidenceOrigin.IMAGE_OCR -> finalPrivacy.content && finalPrivacy.images
                EvidenceOrigin.IMAGE_LABEL -> finalPrivacy.images
                EvidenceOrigin.EMBEDDED_METADATA, EvidenceOrigin.PACKAGE_METADATA, EvidenceOrigin.ARCHIVE_ENTRY -> finalPrivacy.metadata
                else -> true
            } }
            val switches = listOfNotNull(if (!finalPrivacy.metadata) "Metadata inspection is off." else null, if (!finalPrivacy.content) "Content/OCR inspection is off." else null, if (!finalPrivacy.images) "Image analysis is off." else null)
            report("Observed locally. Snippets and labels are evidence, not proof of project ownership.", visible + listOfNotNull(switches.takeIf { it.isNotEmpty() }?.let { EvidenceSection(EvidenceOrigin.COVERAGE, "Privacy and coverage", it, false) }))
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { report("Inspection unavailable: ${failure.message ?: failure.javaClass.simpleName}") }
    }
}
