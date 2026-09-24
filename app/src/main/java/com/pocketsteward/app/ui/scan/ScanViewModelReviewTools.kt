package com.pocketsteward.app.ui.scan

import com.pocketsteward.app.ui.scan.ScanViewModel.Companion.MAX_SIMILARITY_FILES_PER_KIND
import com.pocketsteward.app.ui.scan.ScanViewModel.Companion.MAX_SIMHASH_TEXT_CHARS
import com.pocketsteward.app.ui.scan.ScanViewModel.Companion.MAX_IMAGE_ANALYSIS_FILES
import android.os.Environment
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.ai.AgentModelAvailability
import com.pocketsteward.app.ai.CoherenceClass
import com.pocketsteward.app.ai.SemanticDocument
import com.pocketsteward.app.cleanup.CleanupScopeReport
import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.cleanup.DO_NOT_SORT_TEMPLATE
import com.pocketsteward.app.cleanup.SortCandidate
import com.pocketsteward.app.cleanup.SortScope
import com.pocketsteward.app.cleanup.PlanRequest
import com.pocketsteward.app.cleanup.RuleBasedPlanSource
import com.pocketsteward.app.cleanup.previewLines
import com.pocketsteward.app.content.ContentExtraction
import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.content.ContentMatch
import com.pocketsteward.app.content.index.ContentIndexCandidate
import com.pocketsteward.app.content.index.ContentIndexJob
import com.pocketsteward.app.content.index.ContentIndexJobStatus
import com.pocketsteward.app.content.index.ContentIndexPolicy
import com.pocketsteward.app.content.index.ContentIndexRefreshSummary
import com.pocketsteward.app.content.index.ContentIndexState
import com.pocketsteward.app.content.index.ContentSearchFilters
import com.pocketsteward.app.content.index.ContentSearchSort
import com.pocketsteward.app.content.index.ContentSearchView
import com.pocketsteward.app.content.index.IndexedExtractionStatus
import com.pocketsteward.app.content.index.IndexedFileSearchResult
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskJournalProgress
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.dedupe.DuplicateDetector
import com.pocketsteward.app.dedupe.DuplicateGroup
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.executor.CompositeFileIndex
import com.pocketsteward.app.executor.ExecutionSummary
import com.pocketsteward.app.executor.InMemoryFileIndex
import com.pocketsteward.app.executor.SingleFolderIndex
import com.pocketsteward.app.executor.UndoSummary
import com.pocketsteward.app.image.ImageInsight
import com.pocketsteward.app.metadata.MetadataEnrichment
import com.pocketsteward.app.intent.BoundedIntent
import com.pocketsteward.app.intent.DeterministicIntentParser
import com.pocketsteward.app.intent.IntentAction
import com.pocketsteward.app.intent.IntentOrder
import com.pocketsteward.app.intent.IntentParseResult
import com.pocketsteward.app.intent.IntentPlanGenerator
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.DurablePlan
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.plan.PlanSelection
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.report.ExportResult
import com.pocketsteward.app.report.InventoryExport
import com.pocketsteward.app.report.ProblemSetExport
import com.pocketsteward.app.report.VerifiedTextExporter
import com.pocketsteward.app.report.duplicateTrashReason
import com.pocketsteward.app.picker.PickerFolder
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.plan.ReviewedPlanPackage
import com.pocketsteward.app.rules.RuleEngine
import com.pocketsteward.app.saved.LastScanSession
import com.pocketsteward.app.saved.LastScanRoot
import com.pocketsteward.app.rules.isUncategorized
import com.pocketsteward.app.saved.FavoriteDestination
import com.pocketsteward.app.saved.SavedSearch
import com.pocketsteward.app.scheduled.PendingCleanupSuggestion
import com.pocketsteward.app.scheduled.ScheduledReviewPolicy
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.scan.ScanPhase
import com.pocketsteward.app.scan.ScanProgress
import com.pocketsteward.app.scan.ScanRootSet
import com.pocketsteward.app.scan.classifyByExtension
import com.pocketsteward.app.semantic.CoherenceCandidateSelector
import com.pocketsteward.app.semantic.DestinationPolicy
import com.pocketsteward.app.semantic.SemanticDestinationChoice
import com.pocketsteward.app.semantic.SemanticGroupingEngine
import com.pocketsteward.app.semantic.SemanticPlanAdapter
import com.pocketsteward.app.semantic.SemanticSuggestion
import com.pocketsteward.app.similarity.DocumentSimHash
import com.pocketsteward.app.similarity.ImageDHash
import com.pocketsteward.app.similarity.SimilarityEngine
import com.pocketsteward.app.similarity.SimilarityKind
import com.pocketsteward.app.similarity.SimilaritySignature
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.knownParentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// Duplicate, similar-file, image, metadata, size and age reviews plus inventory/problem-set export.
// Split out of ScanViewModel.kt unchanged; these are extension functions on the
// same ViewModel, so every call site and all behaviour is identical.

/**
 * Plan Section 8's cascade, run on demand rather than during every scan
 * — hashing file contents is exactly what Section 22/23 mean by content
 * inspection, which stays opt-in per action, not automatic.
 */
internal fun ScanViewModel.findDuplicates(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Finding duplicates", "Grouping by size")
        try {
            val mode = summary.mode
            val records = filesForScopes(summary.scopes)
            val detector = DuplicateDetector(
                gateway = container.gatewayFor(mode),
                fileRecordDao = container.database.fileRecordDao(),
            )
            val groups = withContext(Dispatchers.IO) {
                detector.findDuplicates(records) { progress ->
                    _uiState.value = ScanUiState.Working(
                        label = "Finding duplicates",
                        detail = progress.phase,
                        processed = progress.processed,
                        total = progress.total,
                    )
                }
            }
            _uiState.value = ScanUiState.DuplicateReview(groups, summary.scopes)
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

/**
 * Trashes every member of a duplicate group except the first — never a
 * real delete, same `trash()` path as everything else, so the same
 * manual review-in-Trash safety net applies. The Plan Preview screen
 * still shows every one of these before anything moves; this only
 * proposes, it does not execute.
 */
internal fun ScanViewModel.proposeTrashDuplicates(review: ScanUiState.DuplicateReview) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Planning", "Building the trash plan")
        try {
            // `extras` is every copy except the keeper, and the keeper was
            // chosen deterministically by KeeperSelector when the group was
            // built — not "whichever one came back first", which used to
            // mean the surviving copy could differ between runs.
            val operations = review.groups.flatMap { group ->
                group.extras.map { record ->
                    PlannedOperation.Trash(
                        source = parseFileRef(record.stableRef),
                        // The keeper's full path, not its display name.
                        // Two duplicate sets can share a filename, and a
                        // manifest that names the survivor as "cover.jpg"
                        // cannot say which cover.jpg survived.
                        reason = duplicateTrashReason(group.keeper.stableRef),
                        sourceFingerprint = group.sha256,
                    )
                }
            }
            if (operations.isEmpty()) {
                _uiState.value = ScanUiState.Error("No duplicates to trash.")
                return@launch
            }
            showPlanPreview("Trash duplicate files under ${review.scopeLabel}", operations, review.scopes)
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.exportProblemSet(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        try {
            val timestamp = System.currentTimeMillis()
            val written = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val gateway = container.gatewayFor(summary.mode)
            val projectKeywords = settingsRepository.projectKeywords.first()

            for ((scopeIndex, scope) in summary.scopes.withIndex()) {
                _uiState.value = ScanUiState.Working(
                    label = "Exporting unresolved problem set",
                    detail = scope.label,
                    processed = scopeIndex,
                    total = summary.scopes.size,
                )
                val records = withContext(Dispatchers.IO) {
                    container.database.fileRecordDao()
                        .getFilesUnderScopeRoot(scope.root.rawValue())
                }
                val unresolved = records.filter { record ->
                    RuleEngine.classify(
                        record.displayName,
                        record.extension,
                        projectKeywords,
                    ).isUncategorized()
                }
                if (unresolved.isEmpty()) continue

                val base = "POCKETSTEWARD-PROBLEM-SET-${timestamp}"
                val exports = listOf(
                    "${base}.md" to ProblemSetExport.markdown(scope.label, unresolved),
                    "${base}.json" to ProblemSetExport.json(scope.label, unresolved),
                )
                for ((name, body) in exports) {
                    when (val result = withContext(Dispatchers.IO) {
                        VerifiedTextExporter.export(
                            gateway = gateway,
                            parent = scope.root,
                            finalName = name,
                            content = body,
                        )
                    }) {
                        is ExportResult.Written -> {
                            written += result.path
                            if (result.path.startsWith("/")) {
                                container.notifyExternalFileCreated(
                                    result.path,
                                    if (result.path.endsWith(".json")) {
                                        "application/json"
                                    } else {
                                        "text/markdown"
                                    },
                                )
                            }
                        }
                        is ExportResult.Failed -> {
                            errors += "${scope.label}: ${name} · ${result.reason}"
                        }
                    }
                }
            }

            if (written.isEmpty() && errors.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    "No unresolved files were found in this scan.",
                )
                return@launch
            }

            _uiState.value = ScanUiState.ArtifactExportReview(
                title = "Unresolved problem set",
                paths = written,
                errors = errors,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.exportInventory(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        try {
            val timestamp = System.currentTimeMillis()
            val written = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val gateway = container.gatewayFor(summary.mode)

            for ((scopeIndex, scope) in summary.scopes.withIndex()) {
                val root = scope.root
                _uiState.value = ScanUiState.Working(
                    label = "Exporting inventory",
                    detail = scope.label,
                    processed = scopeIndex,
                    total = summary.scopes.size,
                )
                val records = withContext(Dispatchers.IO) {
                    container.database.fileRecordDao().getAllUnderScopeRoot(root.rawValue())
                }
                val base = "POCKETSTEWARD-INVENTORY-$timestamp"
                val exports = listOf(
                    "$base.json" to InventoryExport.json(scope.label, records),
                    "$base.csv" to InventoryExport.csv(records),
                )
                for ((name, body) in exports) {
                    when (val result = withContext(Dispatchers.IO) {
                        VerifiedTextExporter.export(gateway, root, name, body)
                    }) {
                        is ExportResult.Written -> {
                            written += result.path
                            if (result.path.startsWith("/")) {
                                container.notifyExternalFileCreated(
                                    result.path,
                                    if (result.path.endsWith(".json")) "application/json" else "text/csv",
                                )
                            }
                        }
                        is ExportResult.Failed -> errors += "${scope.label}: $name · ${result.reason}"
                    }
                }
            }

            _uiState.value = ScanUiState.ArtifactExportReview(
                title = "Inventory export",
                paths = written,
                errors = errors,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.analyzeImages(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        try {
            val privacy = settingsRepository.privacySettings.first()
            if (!privacy.imageAnalysisEnabled) {
                _uiState.value = ScanUiState.Error(
                    "Image analysis is off. Enable it in Settings before running local image understanding.",
                )
                return@launch
            }

            val records = filesForScopes(summary.scopes)
                .filter {
                    !it.isDirectory &&
                        classifyByExtension(it.extension) == FileCategory.IMAGE
                }
                .sortedByDescending { it.modifiedAt ?: Long.MIN_VALUE }

            val selected = records.take(MAX_IMAGE_ANALYSIS_FILES)
            val insights = mutableListOf<ImageInsight>()
            for ((index, record) in selected.withIndex()) {
                _uiState.value = ScanUiState.Working(
                    label = "Understanding images",
                    detail = record.displayName,
                    processed = index,
                    total = selected.size,
                )
                val insight = withContext(Dispatchers.IO) {
                    container.imageUnderstanding.analyze(record)
                }
                if (insight != null) insights += insight
            }

            _uiState.value = ScanUiState.ImageAnalysisReview(
                scopeLabel = summary.scopeLabel,
                insights = insights,
                attempted = selected.size,
                limited = records.size > selected.size,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.findSimilarFiles(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        try {
            val privacy = settingsRepository.privacySettings.first()
            if (!privacy.imageAnalysisEnabled && !privacy.contentInspectionEnabled) {
                _uiState.value = ScanUiState.Error(
                    "Enable Image analysis and/or Document content inspection in Settings to find near-duplicates.",
                )
                return@launch
            }

            val records = filesForScopes(summary.scopes)
            val byRef = records.associateBy { it.stableRef }
            val signatures = mutableListOf<SimilaritySignature>()
            var imagesAnalyzed = 0
            var documentsAnalyzed = 0

            if (privacy.imageAnalysisEnabled) {
                val images = records.filter {
                    !it.isDirectory && classifyByExtension(it.extension) == FileCategory.IMAGE
                }.take(MAX_SIMILARITY_FILES_PER_KIND)

                for ((index, record) in images.withIndex()) {
                    _uiState.value = ScanUiState.Working(
                        label = "Comparing similar images",
                        detail = record.displayName,
                        processed = index,
                        total = images.size,
                    )
                    val hash = withContext(Dispatchers.IO) {
                        container.imageUnderstanding.perceptualHash(record)
                    }
                    if (hash != null) {
                        signatures += SimilaritySignature(
                            stableRef = record.stableRef,
                            kind = SimilarityKind.IMAGE,
                            hash = hash,
                        )
                        imagesAnalyzed++
                    }
                }
            }

            if (privacy.contentInspectionEnabled) {
                if (summary.mode == StorageAccessMode.DIRECT) {
                    val roots = summary.scopes.map { it.root.rawValue().trimEnd('/') }
                    val repository = container.contentIndexRepository(StorageAccessMode.DIRECT)
                    val indexed = withContext(Dispatchers.IO) {
                        repository.indexedDocuments(roots)
                    }
                        .filter { it.category == FileCategory.DOCUMENT.name }
                        .take(MAX_SIMILARITY_FILES_PER_KIND)

                    for ((index, document) in indexed.withIndex()) {
                        _uiState.value = ScanUiState.Working(
                            label = "Comparing similar documents",
                            detail = document.displayName,
                            processed = index,
                            total = indexed.size,
                        )
                        val text = withContext(Dispatchers.IO) {
                            repository.segments(document.stableRef)
                                .joinToString(" ") { it.body }
                                .take(MAX_SIMHASH_TEXT_CHARS)
                        }
                        val hash = withContext(Dispatchers.Default) { DocumentSimHash.of(text) }
                        if (hash != null) {
                            signatures += SimilaritySignature(
                                stableRef = document.stableRef,
                                kind = SimilarityKind.DOCUMENT,
                                hash = hash,
                            )
                            documentsAnalyzed++
                        }
                    }
                } else {
                    val documents = records.filter {
                        !it.isDirectory &&
                            classifyByExtension(it.extension) == FileCategory.DOCUMENT &&
                            ContentExtractor.supports(it.extension)
                    }.take(MAX_SIMILARITY_FILES_PER_KIND)
                    val inspector = container.contentInspector(StorageAccessMode.SAF)

                    for ((index, record) in documents.withIndex()) {
                        _uiState.value = ScanUiState.Working(
                            label = "Comparing similar documents",
                            detail = record.displayName,
                            processed = index,
                            total = documents.size,
                        )
                        val extraction = withContext(Dispatchers.IO) { inspector.extract(record) }
                        val text = (extraction as? ContentExtraction.Text)
                            ?.content
                            ?.take(MAX_SIMHASH_TEXT_CHARS)
                            .orEmpty()
                        val hash = withContext(Dispatchers.Default) { DocumentSimHash.of(text) }
                        if (hash != null) {
                            signatures += SimilaritySignature(
                                stableRef = record.stableRef,
                                kind = SimilarityKind.DOCUMENT,
                                hash = hash,
                            )
                            documentsAnalyzed++
                        }
                    }
                }
            }

            val groups = withContext(Dispatchers.Default) {
                SimilarityEngine.group(signatures)
            }.mapNotNull { group ->
                val members = group.stableRefs.mapNotNull(byRef::get)
                if (members.size < 2) null else SimilarFileGroup(group.kind, members)
            }

            _uiState.value = ScanUiState.SimilarReview(
                groups = groups,
                scopeLabel = summary.scopeLabel,
                imagesAnalyzed = imagesAnalyzed,
                documentsAnalyzed = documentsAnalyzed,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

/** Plan Section 16's "find the 50 largest files" quick action — browse only, nothing planned yet. */
internal fun ScanViewModel.findLargestFiles(summary: ScanUiState.Summary, limit: Int = 50) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Finding largest files", "Querying the index")
        try {
            val records = filesForScopes(summary.scopes)
                .sortedByDescending { it.sizeBytes }
                .take(limit)
            _uiState.value = ScanUiState.FileListReview("$limit largest files under ${summary.scopeLabel}", records)
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.enrichMetadata(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        try {
            val privacy = settingsRepository.privacySettings.first()
            if (!privacy.metadataIndexingEnabled) {
                _uiState.value = ScanUiState.Error(
                    "Metadata indexing is off. Enable it in Settings before enriching file metadata.",
                )
                return@launch
            }

            val records = filesForScopes(summary.scopes)
            val eligible = records.filter(container.metadataEnricher::supports)
            val entries = mutableListOf<MetadataEnrichment>()
            for ((index, record) in eligible.withIndex()) {
                _uiState.value = ScanUiState.Working(
                    label = "Reading rich metadata",
                    detail = record.displayName,
                    processed = index,
                    total = eligible.size,
                )
                val enrichment = withContext(Dispatchers.IO) {
                    container.metadataEnricher.enrich(record)
                }
                if (enrichment.changed) {
                    withContext(Dispatchers.IO) {
                        container.database.fileRecordDao().upsert(enrichment.record)
                    }
                }
                entries += enrichment
            }

            _uiState.value = ScanUiState.RichMetadataReview(
                scopeLabel = summary.scopeLabel,
                entries = entries,
                attempted = eligible.size,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

/** Plan Section 16's "find old files" quick action — browse only, nothing planned yet. */
internal fun ScanViewModel.findOldFiles(summary: ScanUiState.Summary, olderThanMonths: Int = 6) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Finding old files", "Querying the index")
        try {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30L * olderThanMonths)
            val records = filesForScopes(summary.scopes)
                .filter { it.modifiedAt != null && it.modifiedAt < cutoff }
                .sortedBy { it.modifiedAt }
            _uiState.value = ScanUiState.FileListReview("Files older than $olderThanMonths months under ${summary.scopeLabel}", records)
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}
