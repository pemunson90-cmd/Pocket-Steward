package com.pocketsteward.app.ui.scan

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

// Saved workflows, saved searches, scheduled suggestions and imported/exported reviewed plans.
// Split out of ScanViewModel.kt unchanged; these are extension functions on the
// same ViewModel, so every call site and all behaviour is identical.

internal fun ScanViewModel.startScheduledSuggestion() {
    if (autoStarted) return
    autoStarted = true
    viewModelScope.launch {
        try {
            val suggestion = settingsRepository.pendingCleanupSuggestion.first()
            if (suggestion == null) {
                _uiState.value = ScanUiState.Error(
                    "There is no pending scheduled review. Run a fresh scan instead.",
                )
                return@launch
            }

            val targets = targetsForSavedRoots(
                roots = suggestion.roots,
                label = "Scheduled review",
            )
            if (targets == null) {
                _uiState.value = ScanUiState.Error(
                    "The scheduled review belongs to a storage location that is no longer granted. Re-select that folder or switch storage access.",
                )
                return@launch
            }

            _selectedTargets.value = targets
            startScan(
                targets = targets,
                thenScheduledSuggestion = suggestion,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}


internal fun ScanViewModel.startImportedReviewedPlan(cachePath: String) {
    if (autoStarted) return
    autoStarted = true
    viewModelScope.launch {
        try {
            val access = settingsRepository.storageAccessState.first()
            val json = withContext(Dispatchers.IO) {
                java.io.File(cachePath).takeIf { it.isFile }?.readText()
            } ?: run {
                _uiState.value = ScanUiState.Error("The imported reviewed-plan file is no longer available.")
                return@launch
            }
            val plan = ReviewedPlanPackage.decodeOrNull(json)
            if (plan == null) {
                _uiState.value = ScanUiState.Error("That file is not a valid Pocket Steward reviewed-plan package.")
                return@launch
            }

            val targets = when (access.mode) {
                StorageAccessMode.DIRECT -> {
                    val roots = sourceRootsForImportedPlan(plan)
                    if (roots.isEmpty()) {
                        _uiState.value = ScanUiState.Error(
                            "The imported plan has no direct-file sources to rescan.",
                        )
                        return@launch
                    }
                    roots.map { ScanTarget.CustomFolder(it) }
                }

                StorageAccessMode.SAF -> {
                    if (access.safTreeUri.isNullOrBlank()) {
                        _uiState.value = ScanUiState.Error(
                            "Selected-folder access is no longer available. Re-select the folder before importing this plan.",
                        )
                        return@launch
                    }
                    listOf(ScanTarget.GrantedFolder("Selected folder"))
                }

                null -> {
                    _uiState.value = ScanUiState.Error("Choose storage access before importing a reviewed plan.")
                    return@launch
                }
            }

            _selectedTargets.value = targets
            startScan(
                targets = targets,
                thenImportedPlan = plan,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.exportReviewedPlan(preview: ScanUiState.PlanPreview) {
    viewModelScope.launch {
        try {
            val selected = PlanSelection.selectedOperations(preview.accepted, preview.selectedIndices)
            if (selected.isEmpty()) {
                _uiState.value = ScanUiState.Error("Select at least one action before exporting a reviewed plan.")
                return@launch
            }
            val access = settingsRepository.storageAccessState.first()
            val mode = access.mode ?: run {
                _uiState.value = ScanUiState.Error("No storage access is active.")
                return@launch
            }
            val root = preview.scopes.firstOrNull()?.root ?: run {
                _uiState.value = ScanUiState.Error("This preview no longer has an export folder.")
                return@launch
            }
            val name = "POCKETSTEWARD-REVIEWED-PLAN-${System.currentTimeMillis()}.json"
            val body = ReviewedPlanPackage.encode(preview.goal, selected)
            when (val result = withContext(Dispatchers.IO) {
                VerifiedTextExporter.export(
                    gateway = container.gatewayFor(mode),
                    parent = root,
                    finalName = name,
                    content = body,
                )
            }) {
                is ExportResult.Written -> {
                    if (result.path.startsWith("/")) {
                        container.notifyExternalFileCreated(result.path, "application/json")
                    }
                    _uiState.value = ScanUiState.ArtifactExportReview(
                        title = "Reviewed plan exported",
                        paths = listOf(result.path),
                    )
                }
                is ExportResult.Failed -> {
                    _uiState.value = ScanUiState.Error("Reviewed-plan export failed: ${result.reason}")
                }
            }
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.sourceRootsForImportedPlan(plan: DurablePlan): List<String> {
    val parents = plan.operations.mapNotNull { operation ->
        when (operation) {
            is PlannedOperation.Move -> (operation.source as? FileRef.Direct)?.absolutePath?.substringBeforeLast('/')
            is PlannedOperation.Copy -> (operation.source as? FileRef.Direct)?.absolutePath?.substringBeforeLast('/')
            is PlannedOperation.Rename -> (operation.source as? FileRef.Direct)?.absolutePath?.substringBeforeLast('/')
            is PlannedOperation.Trash -> (operation.source as? FileRef.Direct)?.absolutePath?.substringBeforeLast('/')
            is PlannedOperation.CreateDirectory,
            is PlannedOperation.WriteTextFile,
            -> null
        }
    }
        .filter { it.isNotBlank() }
        .map { it.trimEnd('/') }
        .distinct()
        .sortedBy { it.length }

    return parents.filter { candidate ->
        parents.none { other ->
            other != candidate && candidate.startsWith("$other/")
        }
    }
}

internal fun ScanViewModel.authorizedRootsForImportedPlan(
    operations: List<PlannedOperation>,
    sourceRoots: List<String>,
): List<FileRef.Direct> {
    val plannedDirectories = operations
        .filterIsInstance<PlannedOperation.CreateDirectory>()
        .mapNotNull { op ->
            val parent = op.parent as? FileRef.Direct ?: return@mapNotNull null
            "${parent.absolutePath.trimEnd('/')}/${op.name}"
        }
        .toSet()

    val candidates = operations.mapNotNull { operation ->
        when (operation) {
            is PlannedOperation.CreateDirectory -> operation.parent as? FileRef.Direct
            is PlannedOperation.WriteTextFile -> operation.parent as? FileRef.Direct
            is PlannedOperation.Move,
            is PlannedOperation.Copy,
            -> {
                val destination = when (operation) {
                    is PlannedOperation.Move -> operation.destination
                    is PlannedOperation.Copy -> operation.destination
                    else -> error("unreachable")
                }
                val parentPath = (destination as? FileRef.Direct)
                    ?.absolutePath
                    ?.substringBeforeLast('/', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                if (parentPath in plannedDirectories) null else FileRef.Direct(parentPath)
            }
            is PlannedOperation.Rename,
            is PlannedOperation.Trash,
            -> null
        }
    }
        .filterNot { candidate ->
            val path = candidate.absolutePath.trimEnd('/')
            sourceRoots.any { source ->
                path == source || path.startsWith("$source/")
            }
        }
        .distinctBy { it.absolutePath.trimEnd('/') }
        .sortedBy { it.absolutePath.length }

    return candidates.filter { candidate ->
        candidates.none { other ->
            other !== candidate &&
                candidate.absolutePath.startsWith(other.absolutePath.trimEnd('/') + "/")
        }
    }
}

/** Recreates a saved scope/request from fresh storage state before doing anything else. */
internal fun ScanViewModel.startSavedWorkflow(workflowId: String) {
    if (autoStarted) return
    autoStarted = true
    viewModelScope.launch {
        try {
            val workflow = settingsRepository.savedWorkflows.first()
                .firstOrNull { it.id == workflowId }
            if (workflow == null) {
                _uiState.value = ScanUiState.Error("That saved workflow no longer exists.")
                return@launch
            }
            val targets = targetsForSavedRoots(workflow.roots, workflow.name)
            if (targets == null) {
                _uiState.value = ScanUiState.Error(
                    "This saved workflow belongs to a different storage grant. Re-select that folder or switch storage access before running it.",
                )
                return@launch
            }
            _selectedTargets.value = targets
            startScan(
                targets = targets,
                thenRequest = workflow.request.takeIf { it.isNotBlank() },
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

/** Reopens a saved indexed search after a fresh metadata scan of its roots. */
internal fun ScanViewModel.startSavedSearch(searchId: String) {
    if (autoStarted) return
    autoStarted = true
    viewModelScope.launch {
        try {
            val saved = settingsRepository.savedSearches.first()
                .firstOrNull { it.id == searchId }
            if (saved == null) {
                _uiState.value = ScanUiState.Error("That saved search no longer exists.")
                return@launch
            }
            val targets = targetsForSavedRoots(saved.roots, saved.name)
            if (targets == null) {
                _uiState.value = ScanUiState.Error(
                    "This saved search belongs to a different storage grant. Re-select that folder or switch storage access before opening it.",
                )
                return@launch
            }
            _selectedTargets.value = targets
            startScan(
                targets = targets,
                thenSavedSearch = saved,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.saveIndexedSearch(
    review: ScanUiState.IndexedContentSearchReview,
    name: String,
) {
    viewModelScope.launch {
        try {
            settingsRepository.saveSearch(
                name = name,
                query = review.query,
                roots = review.scopes.map { it.root.rawValue() },
                sort = review.sort,
                filters = review.filters,
                lastResultCount = review.visibleResults.size,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

/** Save a fresh-scan recipe, never a previously validated/executed plan. */
internal fun ScanViewModel.saveWorkflow(
    summary: ScanUiState.Summary,
    name: String,
    request: String,
) {
    viewModelScope.launch {
        try {
            settingsRepository.saveWorkflow(
                name = name,
                request = request,
                roots = summary.scopes.map { it.root.rawValue() },
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal suspend fun ScanViewModel.targetsForSavedRoots(
    roots: List<String>,
    label: String,
): List<ScanTarget>? {
    val normalized = roots
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
    if (normalized.isEmpty()) return null

    val access = settingsRepository.storageAccessState.first()
    return when (access.mode) {
        StorageAccessMode.DIRECT -> {
            if (normalized.any { it.startsWith("content://") || it.startsWith("ps-child:") }) {
                null
            } else {
                normalized.map { ScanTarget.CustomFolder(it) }
            }
        }

        StorageAccessMode.SAF -> {
            val treeUri = access.safTreeUri ?: return null
            val currentRoot = runCatching {
                container.gatewayFor(StorageAccessMode.SAF)
                    .rootOf(
                        StorageScope.Tree(
                            rootRef = FileRef.Saf(treeUri),
                            displayName = label,
                        ),
                    )
                    .rawValue()
                    .trimEnd('/')
            }.getOrNull() ?: return null

            if (normalized.size != 1) {
                null
            } else {
                val savedRoot = normalized.single()
                when {
                    savedRoot == currentRoot ->
                        listOf(ScanTarget.GrantedFolder(label))

                    savedRoot.startsWith("content://") -> {
                        val sameTree = runCatching {
                            DocumentsContract.getTreeDocumentId(Uri.parse(savedRoot)) ==
                                DocumentsContract.getTreeDocumentId(Uri.parse(currentRoot))
                        }.getOrDefault(false)
                        if (sameTree) {
                            listOf(
                                ScanTarget.GrantedSubfolder(
                                    documentUri = savedRoot,
                                    label = label,
                                ),
                            )
                        } else {
                            null
                        }
                    }

                    else -> null
                }
            }
        }

        null -> null
    }
}
