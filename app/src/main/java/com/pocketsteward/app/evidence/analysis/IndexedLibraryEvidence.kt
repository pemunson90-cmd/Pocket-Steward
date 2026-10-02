package com.pocketsteward.app.evidence.analysis

import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.image.ImageReviewBatch
import com.pocketsteward.app.storage.SafScopeAccess
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Inventory-backed admission across currently authorized indexed roots, without moving files. */
suspend fun AppContainer.analyzeIndexedLibrary(retryUnavailable: Boolean = false, automatic: Boolean = false): String? = withContext(Dispatchers.IO) {
    val access = settingsRepository.storageAccessState.first()
    val mode = requireNotNull(access.mode) { "Restore storage access before analysis." }
    val root = requireNotNull(library.root(access)) { "Restore the library root before analysis." }.rawValue().trimEnd('/')
    val privacy = settingsRepository.privacySettings.first()
    require(privacy.contentInspectionEnabled || privacy.imageAnalysisEnabled) { "Enable content inspection or image analysis first." }
    val extensions = (if (privacy.contentInspectionEnabled) ContentExtractor.supportedExtensions else emptySet()) +
        (if (privacy.imageAnalysisEnabled) ImageReviewBatch.EXTENSIONS else emptySet())
    val dao = database.fileRecordDao()
    val roots = (listOf(root) + dao.getKnownScopeRoots()).distinct().filter { candidate ->
        when (mode) {
            StorageAccessMode.DIRECT -> candidate == root || candidate.startsWith("$root/")
            StorageAccessMode.SAF -> SafScopeAccess.contains(appContextForUi, requireNotNull(access.safTreeUri), candidate)
        }
    }
    require(roots.size <= 1024) { "Too many indexed roots. Select a smaller review for analysis." }
    val sources = linkedMapOf<String, EvidenceAnalysisSource>()
    for (scope in roots) {
        currentCoroutineContext().ensureActive()
        dao.libraryFilesByExtension(scope, extensions.toList(), 100_001).forEach { record ->
            sources.putIfAbsent(record.stableRef, EvidenceAnalysisSource(record, scope))
            require(sources.size <= 100_000) { "More than 100,000 eligible files are indexed. Analyze selected inbox reviews separately." }
        }
    }
    if (sources.isEmpty() && automatic) return@withContext null
    require(sources.isNotEmpty()) { "No eligible indexed files yet. Refresh the library inventory first." }
    evidenceAnalysis.start(sources.values.toList(), mode, access.safTreeUri.takeIf { mode == StorageAccessMode.SAF },
        privacy.imageAnalysisEnabled, privacy.contentInspectionEnabled, retryUnavailable, automatic = automatic)
}
