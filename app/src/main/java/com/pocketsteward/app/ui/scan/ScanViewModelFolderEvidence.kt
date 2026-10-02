package com.pocketsteward.app.ui.scan

import android.net.Uri
import android.os.Environment
import com.pocketsteward.app.content.ContentInspectionBudget
import com.pocketsteward.app.content.index.ContentIndexCandidate
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.filing.FilingFolderIndex
import com.pocketsteward.app.image.ImageReviewBatch
import com.pocketsteward.app.plan.SourcePrecondition
import com.pocketsteward.app.plan.SourcePreconditions
import com.pocketsteward.app.storage.DirectProtection
import com.pocketsteward.app.storage.SafProtection
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class FilingFolderMaterial(
    val children: List<FilingArtifact>, val ownerByChild: Map<String, String>, val entryCounts: Map<String, Int>,
    val errors: Map<String, String>, val cachedContentFiles: Int,
) {
    val summary: String get() = "Intact-folder evidence: ${entryCounts.size} folders, ${children.size} indexed files, $cachedContentFiles verified cached text/image results. Analyze all evidence to inspect uncached folder contents in the background."
}

/** Uses names and verified saved evidence only: fresh folder OCR/extraction belongs to the resumable job. */
internal suspend fun ScanViewModel.prepareFilingFolderEvidence(indexed: List<FileRecord>, units: List<FileRecord>,
    baselines: Map<String, SourcePrecondition>, summary: ScanUiState.Summary): FilingFolderMaterial = withContext(Dispatchers.IO) {
    val folders = units.filter { it.isDirectory }
    val graph = FilingFolderIndex(indexed, folders.mapTo(hashSetOf()) { it.stableRef })
    val descendants = graph.descendants(indexed)
    val errors = linkedMapOf<String, String>()
    val children = mutableListOf<FilingArtifact>()
    val owners = linkedMapOf<String, String>()
    val privacy = settingsRepository.privacySettings.first()
    val repository = container.contentIndexRepository(summary.mode)
    var cached = 0
    for ((folderIndex, folder) in folders.withIndex()) {
        currentCoroutineContext().ensureActive()
        _uiState.value = ScanUiState.Working("Checking intact-folder evidence", "${folderIndex + 1} of ${folders.size} · ${folder.displayName}")
        try {
            val expected = requireNotNull(baselines[folder.stableRef])
            require(expected.directoryDigest != null && SourcePreconditions.matches(expected,
                SourcePreconditions.capture(container.gatewayFor(summary.mode), parseFileRef(folder.stableRef)))) { "Folder changed after its original review." }
            val refused = if (summary.mode == StorageAccessMode.DIRECT) {
                @Suppress("DEPRECATION") val storageRoot = Environment.getExternalStorageDirectory().absolutePath
                DirectProtection.refusal(storageRoot, folder.stableRef)
            } else SafProtection(container.appContextForUi).refusal(Uri.parse(folder.stableRef))
            require(refused == null) { refused ?: "Folder protection could not be checked." }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { errors[folder.stableRef] = failure.message ?: "Folder evidence is unavailable."; continue }
        for (indexedChild in descendants[folder.stableRef].orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (indexedChild.isDirectory) continue
            try {
                val record = freshEvidenceRecord(indexedChild, summary.mode)
                if (record.isDirectory) continue
                val root = sourceRootFor(record.stableRef, summary.scopes) ?: summary.scopes.first().root.rawValue()
                var text = ""
                if (privacy.contentInspectionEnabled && com.pocketsteward.app.content.ContentExtractor.supports(record.extension) &&
                    repository.canReuse(ContentIndexCandidate(record, root), ContentInspectionBudget.FILING)) {
                    text = repository.excerpt(record.stableRef, 2_000)
                }
                val image = if (privacy.imageAnalysisEnabled && record.extension.lowercase() in ImageReviewBatch.EXTENSIONS) {
                    container.imageUnderstanding.cached(record, privacy.contentInspectionEnabled)
                        ?: container.imageUnderstanding.cached(record, false)
                } else null
                if (text.isNotBlank() || image != null) cached++
                children += FilingArtifact(record.stableRef, record.displayName, record.extension, record.sizeBytes,
                    record.createdAt, record.modifiedAt, record.parentRef, indexedText = text,
                    imageLabels = image?.labels?.map { it.label }.orEmpty(), imageText = image?.detectedText?.take(2_000))
                owners[record.stableRef] = folder.stableRef
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing/unreadable descendants do not supply ownership evidence. */ }
        }
    }
    FilingFolderMaterial(children, owners, descendants.mapValues { it.value.size }, errors, cached)
}
