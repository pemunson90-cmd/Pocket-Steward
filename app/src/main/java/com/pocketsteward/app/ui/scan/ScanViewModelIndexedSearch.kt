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

// Indexed content search: running, watching, sorting, filtering and refreshing it.
// Split out of ScanViewModel.kt unchanged; these are extension functions on the
// same ViewModel, so every call site and all behaviour is identical.

/**
 * M9 natural-language entry. Parsing is deterministic and offline; the
 * result can only invoke read-only review or build ordinary typed plans.
 */
internal suspend fun ScanViewModel.runIndexedContentSearch(
    summary: ScanUiState.Summary,
    query: String,
    requestedCategories: Set<FileCategory> = emptySet(),
    sort: ContentSearchSort = ContentSearchSort.RELEVANCE,
    filters: ContentSearchFilters? = null,
    savedSearchId: String? = null,
) {
    val privacy = settingsRepository.privacySettings.first()
    if (!privacy.contentInspectionEnabled) {
        _uiState.value = ScanUiState.Error(
            "Document content inspection is off. Enable it in Settings to search inside files.",
        )
        return
    }

    val roots = summary.scopes.map { it.root.rawValue().trimEnd('/') }.distinct()
    val records = filesForScopes(summary.scopes)
    val candidates = records.mapNotNull { record ->
        val root = sourceRootFor(record.stableRef, summary.scopes) ?: return@mapNotNull null
        ContentIndexCandidate(record = record, sourceRoot = root)
    }
    val repository = container.contentIndexRepository(summary.mode)

    // Queue a durable refresh, then search the already-built subset
    // immediately. Indexing continues in its own read-only foreground
    // service and this screen live-refreshes as more segments become
    // searchable.
    withContext(Dispatchers.IO) {
        roots.forEach { root ->
            repository.queueRoot(
                sourceRoot = root,
                eligibleCount = candidates.count { it.sourceRoot.trimEnd('/') == root },
            )
        }
    }
    container.startContentIndexing(roots, summary.mode)

    _uiState.value = ScanUiState.Working(
        label = "Searching indexed contents",
        detail = "Results appear immediately while changed documents index in the background",
    )

    val rows = withContext(Dispatchers.IO) {
        repository.search(query = query, sourceRoots = roots)
    }
    val grouped = withContext(Dispatchers.Default) {
        ContentSearchView.group(rows, query)
    }
    val states = withContext(Dispatchers.IO) {
        roots.mapNotNull { repository.state(it) }
    }
    val refresh = withContext(Dispatchers.IO) {
        repository.jobSummary(roots)
    }
    val jobs = withContext(Dispatchers.IO) {
        repository.jobs(roots)
    }
    val initialFilters = filters ?: ContentSearchFilters(
        categories = requestedCategories.mapTo(linkedSetOf()) { it.name },
    )

    _uiState.value = ScanUiState.IndexedContentSearchReview(
        title = "Content matches for “$query”",
        query = query,
        scopes = summary.scopes,
        requestedCategories = requestedCategories,
        allResults = grouped,
        refreshSummary = refresh,
        indexStates = states,
        indexJobs = jobs,
        sort = sort,
        filters = initialFilters,
        savedSearchId = savedSearchId,
    )
    if (savedSearchId != null) {
        settingsRepository.touchSavedSearch(savedSearchId, grouped.size)
    }

    watchIndexedSearch(
        query = query,
        roots = roots,
        mode = summary.mode,
        savedSearchId = savedSearchId,
    )
}

internal fun ScanViewModel.watchIndexedSearch(
    query: String,
    roots: List<String>,
    mode: StorageAccessMode,
    savedSearchId: String?,
) {
    indexSearchWatchJob?.cancel()
    indexSearchWatchJob = viewModelScope.launch {
        val repository = container.contentIndexRepository(mode)
        while (true) {
            delay(1_000)

            val current = _review.value as? ScanUiState.IndexedContentSearchReview ?: break
            if (current.query != query) break

            val jobs = withContext(Dispatchers.IO) { repository.jobs(roots) }
            val rows = withContext(Dispatchers.IO) {
                repository.search(query = query, sourceRoots = roots)
            }
            val grouped = withContext(Dispatchers.Default) {
                ContentSearchView.group(rows, query)
            }
            val states = withContext(Dispatchers.IO) {
                roots.mapNotNull { repository.state(it) }
            }
            val refresh = withContext(Dispatchers.IO) {
                repository.jobSummary(roots)
            }

            _review.value = current.copy(
                allResults = grouped,
                refreshSummary = refresh,
                indexStates = states,
                indexJobs = jobs,
            )

            val terminal = jobs.isNotEmpty() && jobs.all { job ->
                job.status in setOf(
                    ContentIndexJobStatus.COMPLETED.name,
                    ContentIndexJobStatus.PAUSED.name,
                    ContentIndexJobStatus.FAILED.name,
                )
            }
            if (terminal) {
                if (savedSearchId != null) {
                    settingsRepository.touchSavedSearch(savedSearchId, grouped.size)
                }
                break
            }
        }
    }
}

internal fun ScanViewModel.setIndexedSearchSort(sort: ContentSearchSort) {
    val current = _review.value as? ScanUiState.IndexedContentSearchReview ?: return
    _review.value = current.copy(sort = sort)
}

internal fun ScanViewModel.setIndexedSearchFilters(filters: ContentSearchFilters) {
    val current = _review.value as? ScanUiState.IndexedContentSearchReview ?: return
    _review.value = current.copy(filters = filters)
}

internal fun ScanViewModel.resetIndexedSearchFilters() {
    val current = _review.value as? ScanUiState.IndexedContentSearchReview ?: return
    _review.value = current.copy(
        filters = ContentSearchFilters(
            categories = current.requestedCategories.mapTo(linkedSetOf()) { it.name },
        ),
    )
}

internal fun ScanViewModel.pauseContentIndexing() {
    container.pauseContentIndexing()
}

internal fun ScanViewModel.refreshIndexedSearch(review: ScanUiState.IndexedContentSearchReview) {
    viewModelScope.launch {
        try {
            val summary = _summary.value
            if (summary == null) {
                _uiState.value = ScanUiState.Error("The scan this search came from is no longer available.")
                return@launch
            }
            runIndexedContentSearch(
                summary = summary,
                query = review.query,
                requestedCategories = review.requestedCategories,
                sort = review.sort,
                filters = review.filters,
                savedSearchId = review.savedSearchId,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}
