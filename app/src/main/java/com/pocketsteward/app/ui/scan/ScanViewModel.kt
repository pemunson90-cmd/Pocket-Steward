package com.pocketsteward.app.ui.scan

import android.os.Environment
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
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.plan.PlanSelection
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.report.ExportResult
import com.pocketsteward.app.report.InventoryExport
import com.pocketsteward.app.report.VerifiedTextExporter
import com.pocketsteward.app.report.duplicateTrashReason
import com.pocketsteward.app.picker.PickerFolder
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.rules.RuleEngine
import com.pocketsteward.app.rules.isUncategorized
import com.pocketsteward.app.saved.FavoriteDestination
import com.pocketsteward.app.saved.SavedSearch
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class CategoryStat(val fileCount: Int, val totalBytes: Long)

data class ScanScope(
    val label: String,
    val root: FileRef,
)

/**
 * One folder in the picker. Wraps [PickerFolder], which holds everything the
 * search / sort / filter logic needs and is pure Kotlin so that logic is
 * tested; this adds only the typed ref the rest of the app works in.
 */
data class BrowsableFolder(
    val ref: FileRef.Direct,
    val folder: PickerFolder,
) {
    val displayName: String get() = folder.displayName
    val isProtected: Boolean get() = folder.isProtected
}

/** One folder and whether Smart cleanup is currently allowed to touch its contents. */
data class ProtectableFolder(
    val stableRef: String,
    val displayName: String,
    val fileCount: Int,
    val isProtected: Boolean,
    val scope: ScanScope,
)

data class CoherenceAuditRow(
    val record: FileRecord,
    val classification: CoherenceClass,
    val reason: String,
    val suggestedGroup: String?,
)

data class SimilarFileGroup(
    val kind: SimilarityKind,
    val records: List<FileRecord>,
)


sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val progress: ScanProgress) : ScanUiState
    data class Summary(
        val scopes: List<ScanScope>,
        val mode: StorageAccessMode,
        val totalFiles: Int,
        val totalBytes: Long,
        val byCategory: Map<FileCategory, CategoryStat>,
    ) : ScanUiState {
        init {
            require(scopes.isNotEmpty()) { "A scan summary needs at least one scope." }
        }

        val scopeLabel: String
            get() = if (scopes.size == 1) scopes.single().label else "${scopes.size} selected folders"

        /** Primary scope retained for legacy single-root surfaces such as manifest export. */
        val scopeRoot: FileRef get() = scopes.first().root
    }
    data class PlanPreview(
        val goal: String,
        val accepted: List<PlannedOperation>,
        val rejected: List<RejectedOperation>,
        val scopes: List<ScanScope>,
        /** Root label for each accepted operation, aligned by index. */
        val acceptedScopeLabels: List<String>,
        /** Accepted operations the user still intends to run. Rejected
         * operations never enter this selection set. */
        val selectedIndices: Set<Int> = PlanSelection.allSelected(accepted),
        /**
         * What the generator declined to touch and why, in the user's words
         * rather than counts the screen has to interpret. Empty when a plan
         * source spared nothing, which renders as no section at all.
         */
        val scopeNotes: List<String> = emptyList(),
        /**
         * Extra user-approved destination roots. These are re-listed live
         * and composed with the source scan for validation; PlanValidator is
         * never weakened for cross-root moves.
         */
        val authorizedDestinationRoots: List<FileRef.Direct> = emptyList(),
        /**
         * Set when this plan targets a folder the scanner has never walked
         * (spec 6c's browser). Execution re-validates against a live listing
         * of it rather than the scan index, which for such a folder is empty.
         */
        val unindexedFolder: FileRef? = null,
    ) : ScanUiState {
        init {
            require(scopes.isNotEmpty()) { "A plan preview needs at least one scope." }
        }

        val scopeRoot: FileRef get() = scopes.first().root
        val scopeLabel: String
            get() = if (scopes.size == 1) scopes.single().label else "${scopes.size} selected folders"
    }
    data class ExecutionDone(val summary: ExecutionSummary) : ScanUiState
    data class ExecutionQueued(
        val taskRunId: Long,
        val operationCount: Int,
    ) : ScanUiState
    data class Undoing(
        val taskRunId: Long,
        val completed: Int = 0,
        val total: Int = 0,
    ) : ScanUiState
    data class UndoDone(val summary: UndoSummary) : ScanUiState
    /** Plan Section 8: duplicate candidates found by the size/fingerprint/hash cascade, not yet acted on. */
    data class DuplicateReview(
        val groups: List<DuplicateGroup>,
        val scopes: List<ScanScope>,
    ) : ScanUiState {
        val scopeRoot: FileRef get() = scopes.first().root
        val scopeLabel: String
            get() = if (scopes.size == 1) scopes.single().label else "${scopes.size} selected folders"
    }
    data class SimilarReview(
        val groups: List<SimilarFileGroup>,
        val scopeLabel: String,
        val imagesAnalyzed: Int,
        val documentsAnalyzed: Int,
    ) : ScanUiState

    data class ImageAnalysisReview(
        val scopeLabel: String,
        val insights: List<ImageInsight>,
        val attempted: Int,
        val limited: Boolean,
    ) : ScanUiState

    data class RichMetadataReview(
        val scopeLabel: String,
        val entries: List<MetadataEnrichment>,
        val attempted: Int,
    ) : ScanUiState

    data class ArtifactExportReview(
        val title: String,
        val paths: List<String>,
        val errors: List<String> = emptyList(),
    ) : ScanUiState

    /** Plan Section 16's "find large files" / "find old files" quick actions: browse only, no plan generated. */
    data class FileListReview(val title: String, val records: List<FileRecord>) : ScanUiState
    /** M10A on-demand local content search. Read-only and never persisted to Room. */
    data class ContentSearchReview(
        val title: String,
        val query: String,
        val matches: List<ContentMatch>,
        val inspectedFiles: Int,
        val unsupportedFiles: Int,
        val failedFiles: Int,
        val truncatedResults: Boolean,
    ) : ScanUiState

    data class IndexedContentSearchReview(
        val title: String,
        val query: String,
        val scopes: List<ScanScope>,
        val requestedCategories: Set<FileCategory>,
        val allResults: List<IndexedFileSearchResult>,
        val refreshSummary: ContentIndexRefreshSummary,
        val indexStates: List<ContentIndexState>,
        val indexJobs: List<ContentIndexJob> = emptyList(),
        val sort: ContentSearchSort = ContentSearchSort.RELEVANCE,
        val filters: ContentSearchFilters = ContentSearchFilters(),
        val savedSearchId: String? = null,
    ) : ScanUiState {
        val visibleResults: List<IndexedFileSearchResult>
            get() = ContentSearchView.apply(allResults, sort, filters)

        val indexComplete: Boolean
            get() = when {
                indexJobs.isNotEmpty() -> indexJobs.all {
                    it.status == ContentIndexJobStatus.COMPLETED.name
                }
                else -> indexStates.isNotEmpty() && indexStates.all { it.completed }
            }

        val indexProcessed: Int
            get() = indexJobs.sumOf { it.processedCount }

        val indexEligible: Int
            get() = indexJobs.sumOf { it.eligibleCount }

        val availableRoots: List<String>
            get() = allResults.map { it.sourceRoot }.distinct().sorted()

        val availableCategories: List<String>
            get() = allResults.map { it.category }.distinct().sorted()

        val availableExtensions: List<String>
            get() = allResults.map { it.extension.lowercase() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    data class CoherenceAuditReview(
        val scopes: List<ScanScope>,
        val scopeLabel: String,
        val rows: List<CoherenceAuditRow>,
        val modelName: String?,
        val eligibleDocuments: Int,
        val sampledDocuments: Int,
        val indexedExcerpts: Int,
        val freshExtractions: Int,
        val skippedUnreadable: Int,
        val modelFailures: Int,
        val limited: Boolean,
    ) : ScanUiState

    /**
     * Spec 6c: the in-app folder browser. Directories only — this exists to
     * pick a scan root, and showing files in it would invite the impression
     * that a file can be one.
     */
    data class FolderBrowser(
        val current: FileRef.Direct,
        val currentDisplayName: String,
        val currentIsProtected: Boolean,
        val children: List<BrowsableFolder>,
        val parent: FileRef.Direct?,
    ) : ScanUiState

    /**
     * Spec 6b: every folder under the scan root, with whether a
     * [DO_NOT_SORT_MARKER] is already in it. Protecting one writes that file
     * through the normal plan/preview/journal chain, so it shows up in Task
     * history and can be undone like anything else.
     */
    data class ProtectFolders(
        val scopes: List<ScanScope>,
        val folders: List<ProtectableFolder>,
    ) : ScanUiState {
        val scopeLabel: String
            get() = if (scopes.size == 1) scopes.single().label else "${scopes.size} selected folders"
    }

    /**
     * Any long-running operation that isn't a scan or an undo — hashing for
     * duplicates, generating a plan, executing one. Before this existed, each
     * of those assigned `_uiState` only after finishing, so the screen sat on
     * the previous state for the whole operation and a tap was
     * indistinguishable from a dead button.
     *
     * [processed]/[total] are null where the underlying operation genuinely
     * can't count its work yet, which renders as an indeterminate bar rather
     * than a fake number.
     */
    data class Working(
        val label: String,
        val detail: String? = null,
        val processed: Int? = null,
        val total: Int? = null,
    ) : ScanUiState

    data class Error(val message: String) : ScanUiState
}

/**
 * The scan flow's destinations, as a real back stack rather than one `when`
 * over a single state value.
 *
 * `[device]` M6 shipped all of this as one destination. Opening a review and
 * pressing back called `reset()`, which discarded the scan — on a 22,000 file
 * scope that cost a full re-walk of the filesystem for a back press.
 */
enum class ScanRoute(val route: String) {
    SCAN("scan_flow/scan"),
    PICKER("scan_flow/picker"),
    RESULTS("scan_flow/results"),
    REVIEW("scan_flow/review"),
    PREVIEW("scan_flow/preview"),
    COMPLETION("scan_flow/completion"),
}

/**
 * What a Home quick-action tile wants done once a scan has produced a
 * summary. Home can't run these itself — every one of them needs an indexed
 * scope first — so the tile navigates here, the scan runs with its existing
 * progress UI, and the action fires on arrival.
 */
enum class PostScanAction {
    SMART_CLEANUP,
    FIND_DUPLICATES,
    FIND_LARGEST,
    FIND_OLD,
    REVIEW_UNCATEGORIZED,
    ;

    companion object {
        fun fromRoute(value: String?): PostScanAction? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

class ScanViewModel(
    private val settingsRepository: SettingsRepository,
    private val container: AppContainer,
) : ViewModel() {

    /**
     * The latest outcome of whatever the user last asked for.
     *
     * Through M6 this *was* the screen: one `when` over it rendered
     * everything, and every review's back button called [reset], which set it
     * to [ScanUiState.Idle]. On hardware that meant opening "Files older than
     * 6 months" and pressing back discarded a scan of 22,000 files and forced
     * a full re-walk of the filesystem.
     *
     * It is now an internal event bus. [routeToDestination] fans each value
     * out into the per-destination flow that owns it and emits a navigation
     * event, so back pops a real stack and the expensive thing — [summary] and
     * the Room index behind it — is untouched by anything except a genuinely
     * new scan.
     */
    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    /**
     * The scan result, and the only state here that is expensive to rebuild.
     * Survives every review, preview and completion screen. Cleared only by
     * [reset] or by starting another scan.
     */
    private val _summary = MutableStateFlow<ScanUiState.Summary?>(null)
    val summary: StateFlow<ScanUiState.Summary?> = _summary

    private val _scanning = MutableStateFlow<ScanProgress?>(null)
    val scanning: StateFlow<ScanProgress?> = _scanning

    /** Long operations that are not a scan: hashing, planning, executing. */
    private val _busy = MutableStateFlow<ScanUiState.Working?>(null)
    val busy: StateFlow<ScanUiState.Working?> = _busy

    private val _review = MutableStateFlow<ScanUiState?>(null)
    val review: StateFlow<ScanUiState?> = _review

    private val _preview = MutableStateFlow<ScanUiState.PlanPreview?>(null)
    val preview: StateFlow<ScanUiState.PlanPreview?> = _preview

    private val _completion = MutableStateFlow<ScanUiState?>(null)
    val completion: StateFlow<ScanUiState?> = _completion

    private val _undoProgress = MutableStateFlow<ScanUiState.Undoing?>(null)
    val undoProgress: StateFlow<ScanUiState.Undoing?> = _undoProgress

    private val _picker = MutableStateFlow<ScanUiState.FolderBrowser?>(null)
    val picker: StateFlow<ScanUiState.FolderBrowser?> = _picker

    /**
     * Shown as a dismissible banner on whichever destination is open rather
     * than as a screen of its own. An error that replaced the results was how
     * a failed duplicate pass used to cost a rescan.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * Where the flow should go next. A [Channel] rather than a [StateFlow]:
     * these are one-shot instructions, and a replayed one would re-navigate on
     * every recomposition and on configuration change.
     */
    private val navChannel = Channel<ScanRoute>(Channel.BUFFERED)
    val navEvents: Flow<ScanRoute> = navChannel.receiveAsFlow()

    /** Exposed so a destination can decide which scopes to offer without reaching past the ViewModel. */
    val storageAccessState = settingsRepository.storageAccessState

    val recentFolders: StateFlow<List<String>> = settingsRepository.recentFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteDestinations: StateFlow<List<FavoriteDestination>> = settingsRepository.favoriteDestinations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedTargets = MutableStateFlow<List<ScanTarget>>(emptyList())
    val selectedTargets: StateFlow<List<ScanTarget>> = _selectedTargets

    /** Guards against a recomposition re-triggering a Home tile's auto-scan. */
    private var autoStarted = false

    private var scanJob: Job? = null
    private var indexSearchWatchJob: Job? = null
    private var lastBoundedIntent: BoundedIntent? = null
    private var userScanCancellationRequested: Boolean = false

    init {
        viewModelScope.launch {
            _uiState.collect { routeToDestination(it) }
        }
    }

    /**
     * The fan-out. Every existing call site still assigns [_uiState]; this is
     * the one place that decides which destination owns the result and whether
     * the flow moves.
     *
     * Deliberately does not clear [_summary] for anything but [reset] and a
     * new scan. That single rule is what makes back cheap.
     */
    private suspend fun routeToDestination(state: ScanUiState) {
        when (state) {
            is ScanUiState.Idle -> Unit

            is ScanUiState.Scanning -> {
                _scanning.value = state.progress
                _busy.value = null
            }

            is ScanUiState.Summary -> {
                _summary.value = state
                _scanning.value = null
                _busy.value = null
                _error.value = null
                navChannel.send(ScanRoute.RESULTS)
            }

            is ScanUiState.Working -> _busy.value = state

            is ScanUiState.Undoing -> {
                _undoProgress.value = state
                _busy.value = null
            }

            is ScanUiState.FolderBrowser -> {
                _picker.value = state
                _busy.value = null
                navChannel.send(ScanRoute.PICKER)
            }

            is ScanUiState.DuplicateReview,
            is ScanUiState.SimilarReview,
            is ScanUiState.ImageAnalysisReview,
            is ScanUiState.RichMetadataReview,
            is ScanUiState.ArtifactExportReview,
            is ScanUiState.FileListReview,
            is ScanUiState.ContentSearchReview,
            is ScanUiState.IndexedContentSearchReview,
            is ScanUiState.CoherenceAuditReview,
            is ScanUiState.ProtectFolders,
            -> {
                _review.value = state
                _busy.value = null
                navChannel.send(ScanRoute.REVIEW)
            }

            is ScanUiState.PlanPreview -> {
                _preview.value = state
                _busy.value = null
                navChannel.send(ScanRoute.PREVIEW)
            }

            is ScanUiState.ExecutionDone,
            is ScanUiState.ExecutionQueued,
            is ScanUiState.UndoDone,
            -> {
                _completion.value = state
                _busy.value = null
                _undoProgress.value = null
                navChannel.send(ScanRoute.COMPLETION)
            }

            // Never navigates. The destination the user is on stays on
            // screen, with the message above it, so a failure costs a tap
            // rather than the scan.
            is ScanUiState.Error -> {
                _error.value = state.message
                _busy.value = null
                _scanning.value = null
                _undoProgress.value = null
            }
        }
    }

    fun dismissError() {
        _error.value = null
        // The bus holds the error too, and a StateFlow drops a repeat of the
        // value it already has. Without this, the same failure twice in a row
        // would show the banner once.
        if (_uiState.value is ScanUiState.Error) _uiState.value = ScanUiState.Idle
    }

    /**
     * Leaving a review, preview or completion screen. Drops only that
     * screen's state — never [_summary], which is the whole point of the M7
     * restructure.
     */
    fun onLeftDestination(route: ScanRoute) {
        when (route) {
            ScanRoute.REVIEW -> _review.value = null
            ScanRoute.PREVIEW -> _preview.value = null
            ScanRoute.COMPLETION -> _completion.value = null
            ScanRoute.PICKER -> _picker.value = null
            ScanRoute.SCAN, ScanRoute.RESULTS -> Unit
        }
        if (_uiState.value !is ScanUiState.Idle) _uiState.value = ScanUiState.Idle
    }

    /**
     * Entry point for a Home tile: scan [target], then immediately run
     * [action] against the resulting summary. Idempotent across
     * recompositions — the screen calls this on every composition and only
     * the first one does anything.
     */
    fun startScanThen(target: ScanTarget, action: PostScanAction) {
        if (autoStarted) return
        autoStarted = true
        startScan(target, action)
    }

    /** Home prompt entry: scan first, then interpret the request against that exact snapshot. */
    fun startScanThenRequest(target: ScanTarget, request: String) {
        if (autoStarted) return
        autoStarted = true
        startScan(target, thenRequest = request)
    }


    fun startImportedReviewedPlan(cachePath: String) {
        if (autoStarted) return
        autoStarted = true
        viewModelScope.launch {
            try {
                val access = settingsRepository.storageAccessState.first()
                if (access.mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(
                        "Reviewed plan import requires full storage access so current filesystem state can be revalidated.",
                    )
                    return@launch
                }
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
                val roots = sourceRootsForImportedPlan(plan)
                if (roots.isEmpty()) {
                    _uiState.value = ScanUiState.Error("The imported plan has no direct-file sources to rescan.")
                    return@launch
                }
                val targets = roots.map(::ScanTarget.CustomFolder)
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

    fun exportReviewedPlan(preview: ScanUiState.PlanPreview) {
        viewModelScope.launch {
            try {
                if (preview.scopes.firstOrNull()?.root !is FileRef.Direct) {
                    _uiState.value = ScanUiState.Error("Reviewed-plan export currently requires full storage access.")
                    return@launch
                }
                val selected = PlanSelection.selectedOperations(preview.accepted, preview.selectedIndices)
                if (selected.isEmpty()) {
                    _uiState.value = ScanUiState.Error("Select at least one action before exporting a reviewed plan.")
                    return@launch
                }
                val root = preview.scopes.first().root as FileRef.Direct
                val name = "POCKETSTEWARD-REVIEWED-PLAN-${System.currentTimeMillis()}.json"
                val body = ReviewedPlanPackage.encode(preview.goal, selected)
                when (val result = withContext(Dispatchers.IO) {
                    VerifiedTextExporter.export(
                        gateway = container.gatewayFor(StorageAccessMode.DIRECT),
                        parent = root,
                        finalName = name,
                        content = body,
                    )
                }) {
                    is ExportResult.Written -> {
                        container.notifyExternalFileCreated(result.path, "application/json")
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

    private fun sourceRootsForImportedPlan(plan: DurablePlan): List<String> {
        val parents = plan.operations.mapNotNull { operation ->
            when (operation) {
                is PlannedOperation.Move -> (operation.source as? FileRef.Direct)?.absolutePath?.substringBeforeLast('/')
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

    private fun destinationParentForImportedOperation(operation: PlannedOperation): FileRef.Direct? =
        when (operation) {
            is PlannedOperation.CreateDirectory -> operation.parent as? FileRef.Direct
            is PlannedOperation.Move -> (operation.destination as? FileRef.Direct)?.absolutePath
                ?.substringBeforeLast('/', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
                ?.let(FileRef::Direct)
            is PlannedOperation.Rename,
            is PlannedOperation.Trash,
            -> null
            is PlannedOperation.WriteTextFile -> operation.parent as? FileRef.Direct
        }

    /** Recreates a saved scope/request from fresh storage state before doing anything else. */
    fun startSavedWorkflow(workflowId: String) {
        if (autoStarted) return
        autoStarted = true
        viewModelScope.launch {
            try {
                val access = settingsRepository.storageAccessState.first()
                if (access.mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(
                        "Saved workflows currently require full storage access.",
                    )
                    return@launch
                }
                val workflow = settingsRepository.savedWorkflows.first()
                    .firstOrNull { it.id == workflowId }
                if (workflow == null) {
                    _uiState.value = ScanUiState.Error("That saved workflow no longer exists.")
                    return@launch
                }
                val targets = workflow.roots.map { ScanTarget.CustomFolder(it) }
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
    fun startSavedSearch(searchId: String) {
        if (autoStarted) return
        autoStarted = true
        viewModelScope.launch {
            try {
                val access = settingsRepository.storageAccessState.first()
                if (access.mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(
                        "Saved content searches currently require full storage access.",
                    )
                    return@launch
                }
                val saved = settingsRepository.savedSearches.first()
                    .firstOrNull { it.id == searchId }
                if (saved == null) {
                    _uiState.value = ScanUiState.Error("That saved search no longer exists.")
                    return@launch
                }
                val targets = saved.roots.map { ScanTarget.CustomFolder(it) }
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

    fun saveIndexedSearch(
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
    fun saveWorkflow(
        summary: ScanUiState.Summary,
        name: String,
        request: String,
    ) {
        viewModelScope.launch {
            try {
                if (summary.mode != StorageAccessMode.DIRECT ||
                    summary.scopes.any { it.root !is FileRef.Direct }
                ) {
                    _uiState.value = ScanUiState.Error(
                        "Saved workflows currently require full storage access.",
                    )
                    return@launch
                }
                settingsRepository.saveWorkflow(
                    name = name,
                    request = request,
                    roots = summary.scopes.map { (it.root as FileRef.Direct).absolutePath },
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun toggleScanTarget(target: ScanTarget) {
        val key = target.selectionKey()
        val current = _selectedTargets.value
        _selectedTargets.value = if (current.any { it.selectionKey() == key }) {
            current.filterNot { it.selectionKey() == key }
        } else {
            current + target
        }
    }

    fun addBrowsedFolderToSelection(folder: FileRef.Direct) {
        val target = ScanTarget.CustomFolder(folder.absolutePath)
        if (_selectedTargets.value.none { it.selectionKey() == target.selectionKey() }) {
            _selectedTargets.value = _selectedTargets.value + target
        }
    }

    fun startSelectedScan() {
        val targets = _selectedTargets.value
        if (targets.isEmpty()) {
            _uiState.value = ScanUiState.Error("Select at least one folder to scan.")
            return
        }
        startScan(targets)
    }

    fun startScan(
        target: ScanTarget,
        thenRun: PostScanAction? = null,
        thenRequest: String? = null,
        thenSavedSearch: SavedSearch? = null,
        thenImportedPlan: DurablePlan? = null,
    ) {
        startScan(listOf(target), thenRun, thenRequest, thenSavedSearch, thenImportedPlan)
    }

    fun startScan(
        targets: Collection<ScanTarget>,
        thenRun: PostScanAction? = null,
        thenRequest: String? = null,
        thenSavedSearch: SavedSearch? = null,
        thenImportedPlan: DurablePlan? = null,
    ) {
        scanJob?.cancel()
        userScanCancellationRequested = false

        val job = viewModelScope.launch {
            // A new scan invalidates everything derived from the old one.
            // Doing it here rather than in reset() is what lets back keep the
            // results while "Scan again" still clears them.
            _summary.value = null
            _review.value = null
            _preview.value = null
            _completion.value = null
            _picker.value = null
            _error.value = null
            _uiState.value = ScanUiState.Scanning(ScanProgress(0, null, ScanPhase.SCANNING))

            try {
                val accessState = settingsRepository.storageAccessState.first()
                val mode = accessState.mode
                if (mode == null) {
                    _uiState.value = ScanUiState.Error("No storage access granted yet.")
                    return@launch
                }

                val gateway = container.gatewayFor(mode)
                val scopes = resolveScopes(targets.toList(), mode, accessState.safTreeUri, gateway)
                if (scopes.isEmpty()) {
                    _uiState.value = ScanUiState.Error("Select at least one folder to scan.")
                    return@launch
                }

                val scanner = container.fileScanner(mode)
                var completedBeforeThisRoot = 0

                // Each root remains independent in Room. A paused current
                // root resumes from its durable queue; already-completed
                // earlier roots may be rescanned on the next multi-root run.
                for (scope in scopes) {
                    var thisRootProcessed = 0
                    withContext(Dispatchers.IO) {
                        scanner.scan(scope.root) { progress ->
                            thisRootProcessed = progress.processedCount
                            _uiState.value = ScanUiState.Scanning(
                                progress.copy(
                                    processedCount = completedBeforeThisRoot + progress.processedCount,
                                    currentDirectoryName = progress.currentDirectoryName?.let {
                                        "${scope.label}: $it"
                                    },
                                ),
                            )
                        }
                    }
                    completedBeforeThisRoot += thisRootProcessed
                }

                val records = filesForScopes(scopes)
                val byCategory = records
                    .groupBy { classifyByExtension(it.extension) }
                    .mapValues { (_, files) ->
                        CategoryStat(fileCount = files.size, totalBytes = files.sumOf { it.sizeBytes })
                    }

                val summary = ScanUiState.Summary(
                    scopes = scopes,
                    mode = mode,
                    totalFiles = records.size,
                    totalBytes = records.sumOf { it.sizeBytes },
                    byCategory = byCategory,
                )
                _uiState.value = summary

                targets.filterIsInstance<ScanTarget.CustomFolder>().forEach {
                    settingsRepository.rememberRecentFolder(it.absolutePath)
                }

                when (thenRun) {
                    null -> Unit
                    PostScanAction.SMART_CLEANUP -> proposeSmartCleanup(summary)
                    PostScanAction.FIND_DUPLICATES -> findDuplicates(summary)
                    PostScanAction.FIND_LARGEST -> findLargestFiles(summary)
                    PostScanAction.FIND_OLD -> findOldFiles(summary)
                    PostScanAction.REVIEW_UNCATEGORIZED -> findUncategorized(summary)
                }

                thenRequest?.takeIf { it.isNotBlank() }?.let { request ->
                    handleNaturalLanguage(summary, request)
                }

                thenSavedSearch?.let { saved ->
                    val categories = saved.filters.categories.mapNotNullTo(linkedSetOf()) { name ->
                        FileCategory.entries.firstOrNull { it.name == name }
                    }
                    runIndexedContentSearch(
                        summary = summary,
                        query = saved.query,
                        requestedCategories = categories,
                        sort = saved.sort,
                        filters = saved.filters,
                        savedSearchId = saved.id,
                    )
                }

                thenImportedPlan?.let { imported ->
                    val destinationRoots = imported.operations
                        .mapNotNull(::destinationParentForImportedOperation)
                        .filterNot { destination ->
                            summary.scopes.any { scope ->
                                val root = (scope.root as? FileRef.Direct)?.absolutePath?.trimEnd('/') ?: return@any false
                                destination.absolutePath.trimEnd('/') == root ||
                                    destination.absolutePath.startsWith("$root/")
                            }
                        }
                        .distinctBy { it.absolutePath.trimEnd('/') }

                    showPlanPreview(
                        goal = "Imported reviewed plan · ${imported.goal}",
                        operations = imported.operations,
                        scopes = summary.scopes,
                        scopeNotes = listOf(
                            "Imported plans are never executed directly. Every operation was rescanned and revalidated against current storage.",
                        ),
                        authorizedDestinationRoots = destinationRoots,
                    )
                }
            } catch (cancel: CancellationException) {
                if (userScanCancellationRequested) {
                    _uiState.value = ScanUiState.Error(
                        "Scan paused. Progress was saved; run the same selected folders again to resume.",
                    )
                } else {
                    throw cancel
                }
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }

        scanJob = job
        job.invokeOnCompletion {
            if (scanJob === job) scanJob = null
        }
    }

    fun cancelScan() {
        val job = scanJob ?: return
        if (!job.isActive) return
        userScanCancellationRequested = true
        job.cancel(CancellationException("User paused scan"))
    }

    /**
     * Milestone 2's own exercise of the executor: a hard-coded plan (plan
     * Section 2's own example — "put APK installers under Downloads/APKs")
     * built from what's already indexed, not from natural language or a
     * rule engine. Real request-driven planning is Milestone 4/5.
     */
    fun proposeOrganizeApks(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Planning", "Collecting APKs under ${summary.scopeLabel}")
            try {
                if (summary.scopes.any { it.root !is FileRef.Direct }) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }

                val operations = buildList {
                    for (scope in summary.scopes) {
                        val root = scope.root as FileRef.Direct
                        val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
                        val apkRecords = records.filter { classifyByExtension(it.extension) == FileCategory.APK }
                        if (apkRecords.isEmpty()) continue

                        val apksFolder = FileRef.Direct("${root.absolutePath.trimEnd('/')}/APKs")
                        add(PlannedOperation.CreateDirectory(root, "APKs", "Destination for Android package installers"))
                        for (record in apkRecords) {
                            add(
                                PlannedOperation.Move(
                                    source = FileRef.Direct(record.stableRef),
                                    destination = FileRef.Direct("${apksFolder.absolutePath}/${record.displayName}"),
                                    reason = "APK file",
                                ),
                            )
                        }
                    }
                }
                if (operations.isEmpty()) {
                    _uiState.value = ScanUiState.Error("No APKs found under ${summary.scopeLabel}.")
                    return@launch
                }

                showPlanPreview("Organize APKs under ${summary.scopeLabel}", operations, summary.scopes)
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Plan Section 4/9: the rule engine plans, no model involved. Pulls
     * [SettingsRepository.projectKeywords] so a user-configured term like
     * "Leaseworld" groups by project before falling back to a plain
     * extension category, then runs the result through the same
     * [PlanValidator] every other plan goes through — nothing about being
     * rule-generated exempts it from validation.
     */

    /**
     * M10B's first model feature. Read-only by construction: content is
     * extracted through ContentInspector, AgentModel receives bounded text,
     * and the result is rendered as advice rather than PlannedOperation.
     */
    fun runCoherenceAudit(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working(
                "Coherence audit",
                "Selecting a representative cross-section from the local index",
            )
            try {
                if (summary.mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }

                val privacy = settingsRepository.privacySettings.first()
                if (!privacy.contentInspectionEnabled) {
                    _uiState.value = ScanUiState.Error(
                        "Document content inspection is off. Enable it in Settings before running a coherence audit.",
                    )
                    return@launch
                }
                if (!privacy.onDeviceAiEnabled) {
                    _uiState.value = ScanUiState.Error(
                        "On-device AI is off. Enable it in Settings before running a coherence audit.",
                    )
                    return@launch
                }

                when (container.agentModel.availability()) {
                    AgentModelAvailability.AVAILABLE -> Unit
                    AgentModelAvailability.DOWNLOADABLE -> {
                        _uiState.value = ScanUiState.Error(
                            "Gemini Nano is available to download. Open Settings and tap Download Gemini Nano.",
                        )
                        return@launch
                    }
                    AgentModelAvailability.DOWNLOADING -> {
                        _uiState.value = ScanUiState.Error("Gemini Nano is still downloading.")
                        return@launch
                    }
                    AgentModelAvailability.UNAVAILABLE -> {
                        _uiState.value = ScanUiState.Error(
                            "Gemini Nano is unavailable on this device or current AICore configuration.",
                        )
                        return@launch
                    }
                }

                val records = filesForScopes(summary.scopes)
                    .filter { !it.isDirectory && ContentExtractor.supports(it.extension) }
                val selected = CoherenceCandidateSelector.select(records)
                if (selected.isEmpty()) {
                    _uiState.value = ScanUiState.Error(
                        "No readable text documents were available for the coherence audit.",
                    )
                    return@launch
                }

                val repository = container.contentIndexRepository(summary.mode)
                val inspector = container.contentInspector(summary.mode)
                val documents = mutableListOf<SemanticDocument>()
                val recordById = linkedMapOf<String, FileRecord>()
                var skippedUnreadable = 0
                var indexedExcerpts = 0
                var freshExtractions = 0

                for ((index, record) in selected.withIndex()) {
                    _uiState.value = ScanUiState.Working(
                        label = "Coherence audit",
                        detail = "Preparing representative document ${index + 1} of ${selected.size}: ${record.displayName}",
                        processed = index,
                        total = selected.size,
                    )

                    val existing = withContext(Dispatchers.IO) {
                        repository.indexedDocument(record.stableRef)
                    }
                    val canUseIndex = ContentIndexPolicy.canReuse(existing, record) &&
                        existing?.extractionStatus == IndexedExtractionStatus.INDEXED.name

                    val normalized = if (canUseIndex) {
                        val segments = withContext(Dispatchers.IO) {
                            repository.segments(record.stableRef)
                        }
                        val excerpt = buildString {
                            for (segment in segments) {
                                if (isNotEmpty()) append(' ')
                                append(segment.body)
                                if (length >= COHERENCE_EXCERPT_CHARS) break
                            }
                        }
                            .replace(Regex("""\s+"""), " ")
                            .trim()
                            .take(COHERENCE_EXCERPT_CHARS)
                        if (excerpt.isNotBlank()) indexedExcerpts++
                        excerpt
                    } else {
                        when (val extraction = withContext(Dispatchers.IO) { inspector.extract(record) }) {
                            is ContentExtraction.Text -> {
                                freshExtractions++
                                extraction.content
                                    .replace(Regex("""\s+"""), " ")
                                    .trim()
                                    .take(COHERENCE_EXCERPT_CHARS)
                            }
                            is ContentExtraction.Unsupported,
                            is ContentExtraction.Failed,
                            -> ""
                        }
                    }

                    if (normalized.isBlank()) {
                        skippedUnreadable++
                        continue
                    }

                    val id = record.stableRef
                    documents += SemanticDocument(
                        id = id,
                        displayName = record.displayName,
                        sourcePath = record.stableRef,
                        excerpt = normalized,
                    )
                    recordById[id] = record
                }

                if (documents.isEmpty()) {
                    _uiState.value = ScanUiState.Error(
                        "The representative sample contained no readable document text.",
                    )
                    return@launch
                }

                val rows = mutableListOf<CoherenceAuditRow>()
                var modelName: String? = null
                var modelFailures = 0
                val batches = documents.chunked(COHERENCE_BATCH_SIZE)

                for ((batchIndex, batch) in batches.withIndex()) {
                    _uiState.value = ScanUiState.Working(
                        label = "Coherence audit",
                        detail = "Analyzing representative batch ${batchIndex + 1} of ${batches.size} on device",
                        processed = batchIndex,
                        total = batches.size,
                    )

                    val audit = try {
                        container.agentModel.coherenceAudit(summary.scopeLabel, batch)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        modelFailures += batch.size
                        continue
                    }
                    if (modelName == null) modelName = audit.modelName

                    audit.findings.forEach { finding ->
                        val record = recordById[finding.id] ?: return@forEach
                        val actionable = finding.classification in setOf(
                            CoherenceClass.QUESTIONABLE,
                            CoherenceClass.DOES_NOT_BELONG,
                        )
                        rows += CoherenceAuditRow(
                            record = record,
                            classification = finding.classification,
                            reason = finding.reason,
                            suggestedGroup = finding.suggestedGroup.takeIf { actionable },
                        )
                    }
                }

                if (rows.isEmpty()) {
                    _uiState.value = ScanUiState.Error(
                        "On-device intelligence could not classify the representative sample. No files were changed.",
                    )
                    return@launch
                }

                _uiState.value = ScanUiState.CoherenceAuditReview(
                    scopes = summary.scopes,
                    scopeLabel = summary.scopeLabel,
                    rows = rows,
                    modelName = modelName,
                    eligibleDocuments = records.size,
                    sampledDocuments = documents.size,
                    indexedExcerpts = indexedExcerpts,
                    freshExtractions = freshExtractions,
                    skippedUnreadable = skippedUnreadable,
                    modelFailures = modelFailures,
                    limited = records.size > documents.size || modelFailures > 0,
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * M11B: convert advisory coherence findings into an ordinary validated
     * plan. The model never supplies paths and never reaches the executor.
     */
    fun proposeSemanticOrganization(
        review: ScanUiState.CoherenceAuditReview,
        includeSubfolders: Boolean = false,
        destinationPolicy: DestinationPolicy = DestinationPolicy.ROOT_LOCAL,
        explicitDestinationPath: String? = null,
    ) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working(
                "Building organization proposal",
                "Resolving the approved destination and validating semantic suggestions",
            )
            try {
                if (review.scopes.any { it.root !is FileRef.Direct }) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }

                @Suppress("DEPRECATION")
                val documentsRoot = FileRef.Direct(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
                )

                val explicitRoot = if (destinationPolicy == DestinationPolicy.EXPLICIT_FOLDER) {
                    val path = explicitDestinationPath?.trim()?.trimEnd('/')
                    if (path.isNullOrBlank()) {
                        _uiState.value = ScanUiState.Error("Choose an existing destination folder first.")
                        return@launch
                    }
                    val ref = FileRef.Direct(path)
                    val gateway = container.gatewayFor(StorageAccessMode.DIRECT)
                    val valid = withContext(Dispatchers.IO) {
                        gateway.exists(ref) && runCatching { gateway.stat(ref).isDirectory }.getOrDefault(false)
                    }
                    if (!valid) {
                        _uiState.value = ScanUiState.Error("That destination folder does not exist or is not a directory.")
                        return@launch
                    }
                    ref
                } else {
                    null
                }

                val destinationChoice = SemanticDestinationChoice(
                    policy = destinationPolicy,
                    explicitRoot = explicitRoot,
                )

                val records = allRecordsForScopes(review.scopes)
                val documentRecords = records.filter {
                    !it.isDirectory && ContentExtractor.supports(it.extension)
                }
                val projectKeywords = settingsRepository.projectKeywords.first()
                val correctionRules = settingsRepository.correctionRules.first()
                val indexedEvidence = linkedMapOf<String, String>()
                if (projectKeywords.isNotEmpty()) {
                    val roots = review.scopes.map { it.root.rawValue().trimEnd('/') }
                    val repository = container.contentIndexRepository(StorageAccessMode.DIRECT)
                    for (keyword in projectKeywords) {
                        val hits = withContext(Dispatchers.IO) {
                            runCatching {
                                repository.search(
                                    query = keyword.term,
                                    sourceRoots = roots,
                                    limit = 20_000,
                                )
                            }.getOrDefault(emptyList())
                        }
                        hits.forEach { hit ->
                            indexedEvidence[hit.stableRef] =
                                indexedEvidence[hit.stableRef].orEmpty() + " " + keyword.term
                        }
                    }
                }

                val modelSuggestions = review.rows.map { row ->
                    SemanticSuggestion(
                        stableRef = row.record.stableRef,
                        classification = row.classification,
                        suggestedGroup = row.suggestedGroup,
                    )
                }
                val groupingDecisions = withContext(Dispatchers.Default) {
                    SemanticGroupingEngine.decide(
                        records = documentRecords,
                        projectKeywords = projectKeywords,
                        correctionRules = correctionRules,
                        indexedTextByRef = indexedEvidence,
                        modelSuggestions = modelSuggestions,
                    )
                }
                val result = withContext(Dispatchers.Default) {
                    SemanticPlanAdapter.build(
                        scopeRoots = review.scopes.map { it.root as FileRef.Direct },
                        records = records,
                        suggestions = groupingDecisions.map { it.suggestion },
                        includeSubfolders = includeSubfolders,
                        destinationChoice = destinationChoice,
                        recommendedDocumentsRoot = documentsRoot,
                    )
                }

                if (result.operations.isEmpty()) {
                    val topReason = result.skipped
                        .groupingBy { it.reason }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key
                    _uiState.value = ScanUiState.Error(
                        buildString {
                            append("The audit produced no safe file moves to propose.")
                            topReason?.let { append(" Most skipped items: $it") }
                        },
                    )
                    return@launch
                }

                val destinationLabel = when (destinationPolicy) {
                    DestinationPolicy.ROOT_LOCAL -> "inside each current scan root"
                    DestinationPolicy.RECOMMENDED_DOCUMENTS -> documentsRoot.absolutePath
                    DestinationPolicy.EXPLICIT_FOLDER -> explicitRoot!!.absolutePath
                }

                val notes = buildList {
                    add("Semantic findings are advisory. This proposal was rebuilt deterministically from the current scan.")
                    add("Grouping evidence priority: learned corrections → project keywords → repeated filename/title signals → indexed content → model advice.")
                    add("Approved destination: $destinationLabel.")
                    if (groupingDecisions.isNotEmpty()) {
                        val evidenceSummary = groupingDecisions
                            .groupingBy { it.evidence.name }
                            .eachCount()
                            .entries
                            .sortedByDescending { it.value }
                            .joinToString(" · ") { (evidence, count) ->
                                "${evidence.lowercase().replace('_', ' ')}: $count"
                            }
                        add("Strong grouping candidates: ${groupingDecisions.size} · $evidenceSummary")
                    }
                    if (!includeSubfolders) {
                        add("Files already inside folders were left alone unless nested moves were explicitly enabled.")
                    }
                    val protected = result.skipped.count { it.reason.contains("protected", ignoreCase = true) }
                    if (protected > 0) add("$protected file(s) stayed untouched inside protected folders.")
                    val unsafe = result.skipped.count { it.reason.contains("unsafe", ignoreCase = true) }
                    if (unsafe > 0) add("$unsafe unsafe or unusable suggested group name(s) were ignored.")
                }

                showPlanPreview(
                    goal = "Organize ${result.plannedFileCount} file(s) from semantic findings",
                    operations = result.operations,
                    scopes = review.scopes,
                    scopeNotes = notes,
                    authorizedDestinationRoots = result.authorizedDestinationRoots,
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun proposeSmartCleanup(summary: ScanUiState.Summary, includeSubfolders: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Planning cleanup", "Classifying files under ${summary.scopeLabel}")
            try {
                if (summary.scopes.any { it.root !is FileRef.Direct }) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }

                val projectKeywords = settingsRepository.projectKeywords.first()
                val generatedByScope = summary.scopes.map { scope ->
                    val root = scope.root as FileRef.Direct
                    val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
                    val generated = withContext(Dispatchers.Default) {
                        RuleBasedPlanSource.proposePlan(
                            PlanRequest(
                                scopeRoot = root,
                                records = records,
                                projectKeywords = projectKeywords,
                                includeSubfolders = includeSubfolders,
                            ),
                        )
                    }
                    scope to generated
                }

                val operations = generatedByScope.flatMap { it.second.plan.operations }
                val scopeNotes = generatedByScope.flatMap { (scope, generated) ->
                    generated.scopeReport.previewLines().map { line ->
                        if (summary.scopes.size == 1) line else "${scope.label}: $line"
                    }
                }
                if (operations.isEmpty()) {
                    val message = if (generatedByScope.size == 1) {
                        val (scope, generated) = generatedByScope.single()
                        nothingToProposeMessage(scope.label, generated.scopeReport)
                    } else {
                        "Nothing across the selected folders could be proposed safely. " +
                            "Protected and already-organized files were left where they are."
                    }
                    _uiState.value = ScanUiState.Error(message)
                    return@launch
                }

                val goal = if (summary.scopes.size == 1) {
                    generatedByScope.single().second.plan.goal
                } else {
                    "Smart cleanup across ${summary.scopes.size} selected folders"
                }
                showPlanPreview(goal, operations, summary.scopes, scopeNotes)
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * "Nothing to propose" has three different causes now that scope is
     * bounded, and they need three different answers. Telling someone their
     * files "couldn't be classified" when the real reason is that every one
     * of them is inside a subfolder sends them to the wrong screen.
     */
    private fun nothingToProposeMessage(scopeLabel: String, report: CleanupScopeReport): String = when {
        report.skippedByProtection > 0 && report.skippedByDepth == 0 ->
            "Everything under $scopeLabel is inside a folder protected by $DO_NOT_SORT_MARKER — nothing to propose."
        report.skippedByDepth > 0 ->
            "Nothing is loose directly in $scopeLabel. ${report.skippedByDepth} files are already inside folders, and Smart cleanup leaves those alone unless you turn on \"Include files in subfolders\"."
        else ->
            "Nothing under $scopeLabel could be classified with full confidence — nothing to propose. Review uncategorized to see what was skipped and why."
    }

    /**
     * Spec 6c. Opens the folder browser at external storage root, or at
     * [startAt] when descending.
     *
     * Reads the filesystem directly rather than the index, because the whole
     * point is picking a folder that has never been scanned — an
     * index-backed browser could only ever offer folders already inside a
     * scope, which is the limitation this removes.
     *
     * Direct mode only. In SAF mode there is exactly one reachable tree and
     * narrowing inside it is what the granted-folder target already does, so
     * the browser is not offered rather than offered and then refusing.
     */
    fun browseFolders(startAt: FileRef.Direct? = null) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Reading folders", startAt?.absolutePath ?: "Storage root")
            try {
                val mode = settingsRepository.storageAccessState.first().mode
                if (mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }
                @Suppress("DEPRECATION")
                val storageRoot = FileRef.Direct(Environment.getExternalStorageDirectory().absolutePath)
                val current = startAt ?: storageRoot
                val gateway = container.gatewayFor(mode)

                val children = withContext(Dispatchers.IO) {
                    gateway.listChildren(current)
                        .filter { it.isDirectory }
                        .mapNotNull { entry -> (entry.ref as? FileRef.Direct)?.let { entry.displayName to it } }
                        .map { (name, ref) -> describeFolder(gateway, ref, name) }
                }

                _uiState.value = ScanUiState.FolderBrowser(
                    current = current,
                    currentDisplayName = current.absolutePath.trimEnd('/').substringAfterLast('/')
                        .ifBlank { current.absolutePath },
                    // The folder you are standing in, not just the ones
                    // below it: otherwise a folder with no subfolders could
                    // never be protected from here at all.
                    currentIsProtected = withContext(Dispatchers.IO) { gateway.exists(current.markerRef()) },
                    children = children,
                    // Never above external storage root: there is nothing
                    // useful up there and MANAGE_EXTERNAL_STORAGE does not
                    // reach it anyway.
                    parent = current.parentWithin(storageRoot),
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Counts and measures one folder's **direct** contents, not its whole
     * subtree. One extra listing per child folder, which is bounded and fast
     * enough for a picker; a recursive size would mean walking the entire
     * device to draw a list.
     *
     * The picker says "direct contents" on screen for the same reason: a
     * number labelled "size" that silently means something narrower is worse
     * than no number.
     */
    private suspend fun describeFolder(
        gateway: StorageGateway,
        ref: FileRef.Direct,
        displayName: String,
    ): BrowsableFolder {
        val contents = runCatching { gateway.listChildren(ref) }.getOrDefault(emptyList())
        val files = contents.filter { !it.isDirectory }
        val stats = files.mapNotNull { runCatching { gateway.stat(it.ref) }.getOrNull() }
        return BrowsableFolder(
            ref = ref,
            folder = PickerFolder(
                path = ref.absolutePath,
                displayName = displayName,
                fileCount = files.size,
                totalBytes = stats.sumOf { it.sizeBytes },
                // mapNotNull-then-maxOrNull, not maxOfOrNull: modifiedAtEpochMs
                // is Long?, and maxOfOrNull requires R : Comparable<R>, which
                // a nullable type is not. Dropping the unknowns first is also
                // the behaviour wanted — a file with no timestamp should not
                // decide the folder's.
                modifiedAt = stats.mapNotNull { it.modifiedAtEpochMs }.maxOrNull()
                    ?: runCatching { gateway.stat(ref).modifiedAtEpochMs }.getOrNull(),
                // The marker is a known filename, so the listing already read
                // above answers this — no extra stat needed.
                isProtected = contents.any { !it.isDirectory && it.displayName == DO_NOT_SORT_MARKER },
            ),
        )
    }

    /** Scans the folder currently open in the browser, as its own scope root. */
    fun scanBrowsedFolder(folder: FileRef.Direct) {
        startScan(ScanTarget.CustomFolder(folder.absolutePath))
    }

    /**
     * Protects or unprotects [folder] from the browser, so spec 6b applies
     * to any folder on the device rather than only to ones already inside a
     * scanned scope.
     *
     * Both directions are plans, not direct calls, and both go through the
     * preview. Unprotecting **trashes** the marker rather than deleting it —
     * the same rule as everything else here, and it means an accidental
     * unprotect is recoverable from the Trash screen like any other file.
     */
    fun proposeToggleProtection(state: ScanUiState.FolderBrowser, folder: BrowsableFolder) {
        proposeToggleProtection(state.current, folder.ref, folder.displayName, folder.isProtected)
    }

    /** The same toggle for the folder currently open, rather than one listed inside it. */
    fun proposeToggleProtectionHere(state: ScanUiState.FolderBrowser) {
        proposeToggleProtection(state.current, state.current, state.currentDisplayName, state.currentIsProtected)
    }

    private fun proposeToggleProtection(
        root: FileRef.Direct,
        target: FileRef.Direct,
        displayName: String,
        isProtected: Boolean,
    ) {
        viewModelScope.launch {
            try {
                val operation = if (isProtected) {
                    PlannedOperation.Trash(
                        source = target.markerRef(),
                        reason = "Removes protection from $displayName. The marker file goes to Trash, " +
                            "not deleted, so this is reversible.",
                    )
                } else {
                    PlannedOperation.WriteTextFile(
                        parent = target,
                        name = DO_NOT_SORT_MARKER,
                        content = DO_NOT_SORT_TEMPLATE,
                        reason = "Marks $displayName as off limits to Smart cleanup until this file is removed.",
                    )
                }
                // The browser reaches folders that were never scanned, so
                // the validator's index has to be built from the folder
                // itself rather than from a scan scope that may not exist.
                showUnindexedPlanPreview(
                    goal = if (isProtected) "Unprotect $displayName" else "Protect $displayName",
                    operations = listOf(operation),
                    root = root,
                    // A Trash of the marker is validated against the folder
                    // holding it, which is the target itself, not its parent.
                    folder = target,
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Spec 6b. Lists the folders under a scanned scope and whether each one
     * already holds a [DO_NOT_SORT_MARKER], entirely from the index — no
     * filesystem read, because the scan already recorded every folder and
     * every file in it.
     */
    fun reviewFolderProtection(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Reading folders", "Checking which are already protected")
            try {
                if (summary.scopes.any { it.root !is FileRef.Direct }) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }

                val folders = summary.scopes.flatMap { scope ->
                    val root = scope.root as FileRef.Direct
                    val records = container.database.fileRecordDao().getAllUnderScopeRoot(root.rawValue())
                    withContext(Dispatchers.Default) {
                        val protectedFolders = SortScope.protectedFolders(records.map { it.toSortCandidate() })
                        val fileCounts = records.filter { !it.isDirectory }.groupingBy { it.parentRef }.eachCount()
                        records
                            .filter { it.isDirectory && it.stableRef != root.rawValue() }
                            .sortedBy { it.stableRef }
                            .map { folder ->
                                ProtectableFolder(
                                    stableRef = folder.stableRef,
                                    displayName = folder.stableRef.removePrefix(root.absolutePath.trimEnd('/')).trimStart('/'),
                                    fileCount = fileCounts[folder.stableRef] ?: 0,
                                    isProtected = folder.stableRef in protectedFolders,
                                    scope = scope,
                                )
                            }
                    }
                }
                _uiState.value = ScanUiState.ProtectFolders(summary.scopes, folders.distinctBy { it.stableRef })
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Proposes writing a protection marker into [folder]. Deliberately a
     * one-operation plan rather than a direct gateway call: spec item 7 wants
     * an AI's first mutation-adjacent power to be exactly this, and routing
     * the manual version through the same validate/preview/journal chain is
     * what makes the AI version a change of planner rather than a new path
     * into the mutation layer.
     */
    fun proposeToggleProtection(state: ScanUiState.ProtectFolders, folder: ProtectableFolder) {
        viewModelScope.launch {
            try {
                val folderRef = parseFileRef(folder.stableRef)
                val operation = if (folder.isProtected) {
                    // The marker was indexed by the scan like any other
                    // file, so the ordinary scan-index preview validates
                    // this — unlike the browser, which reaches folders no
                    // scan has walked.
                    PlannedOperation.Trash(
                        source = parseFileRef("${folder.stableRef.trimEnd('/')}/$DO_NOT_SORT_MARKER"),
                        reason = "Removes protection from ${folder.displayName}. The marker file goes to Trash, " +
                            "not deleted, so this is reversible.",
                    )
                } else {
                    PlannedOperation.WriteTextFile(
                        parent = folderRef,
                        name = DO_NOT_SORT_MARKER,
                        content = DO_NOT_SORT_TEMPLATE,
                        reason = "Marks ${folder.displayName} as off limits to Smart cleanup until this file is removed.",
                    )
                }
                val goal = if (folder.isProtected) "Unprotect ${folder.displayName}" else "Protect ${folder.displayName}"
                showPlanPreview(goal, listOf(operation), listOf(folder.scope))
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Plan Section 9's ambiguous set, made visible: every file the rule
     * engine could not place with full confidence, which is exactly what
     * Smart cleanup silently skips. Read-only — this is the honest answer to
     * "why didn't it move that file", and later the input an AI planner takes.
     */
    fun findUncategorized(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Reviewing", "Checking what the rules can't place")
            try {
                val records = filesForScopes(summary.scopes)
                val projectKeywords = settingsRepository.projectKeywords.first()
                val uncategorized = withContext(Dispatchers.Default) {
                    records.filter { RuleEngine.classify(it.displayName, it.extension, projectKeywords).isUncategorized() }
                }
                _uiState.value = ScanUiState.FileListReview(
                    title = "Uncategorized under ${summary.scopeLabel}",
                    records = uncategorized,
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * The single path a plan takes to the screen, whatever produced it:
     * validate, then preview. Nothing calls `PlanExecutor` without passing
     * through here first, which is what keeps plan Decision 1 true when a
     * second [com.pocketsteward.app.cleanup.PlanSource] (the AI one) arrives.
     */
    private suspend fun showPlanPreview(
        goal: String,
        operations: List<PlannedOperation>,
        scopes: List<ScanScope>,
        scopeNotes: List<String> = emptyList(),
        authorizedDestinationRoots: List<FileRef.Direct> = emptyList(),
    ) {
        val index = buildAuthorizedPlanIndex(
            scopes = scopes,
            destinationRoots = authorizedDestinationRoots,
            mode = StorageAccessMode.DIRECT,
        )
        val validated = PlanValidator.validate(operations, index)
        _uiState.value = ScanUiState.PlanPreview(
            goal = goal,
            accepted = validated.accepted,
            rejected = validated.rejected,
            scopes = scopes,
            acceptedScopeLabels = validated.accepted.map { operation ->
                scopeForOperation(operation, scopes)?.label ?: scopes.first().label
            },
            scopeNotes = scopeNotes,
            authorizedDestinationRoots = authorizedDestinationRoots,
        )
    }

    private suspend fun buildAuthorizedPlanIndex(
        scopes: List<ScanScope>,
        destinationRoots: List<FileRef.Direct>,
        mode: StorageAccessMode,
    ): FileIndex {
        val delegates = mutableListOf<FileIndex>(
            InMemoryFileIndex(allRecordsForScopes(scopes)),
        )
        if (destinationRoots.isNotEmpty()) {
            require(mode == StorageAccessMode.DIRECT) {
                "Cross-root destination authorization currently requires direct storage access."
            }
            val gateway = container.gatewayFor(mode)
            destinationRoots
                .distinctBy { it.absolutePath.trimEnd('/') }
                .forEach { root ->
                    val children = withContext(Dispatchers.IO) { gateway.listChildren(root) }
                    delegates += SingleFolderIndex(root, children)
                }
        }
        return if (delegates.size == 1) delegates.single() else CompositeFileIndex(delegates)
    }

    private suspend fun showPlanPreview(
        goal: String,
        operations: List<PlannedOperation>,
        root: FileRef,
        scopeNotes: List<String> = emptyList(),
    ) {
        showPlanPreview(
            goal = goal,
            operations = operations,
            scopes = listOf(ScanScope(root.displayScopeLabel(), root)),
            scopeNotes = scopeNotes,
        )
    }

    /**
     * The same validate-then-preview path [showPlanPreview] takes, for a
     * folder the scan index has never seen (spec 6c's browser). The index is
     * one live listing of [folder] rather than a scan scope, which is
     * exactly as much as the operations involved need and no more.
     */
    private suspend fun showUnindexedPlanPreview(
        goal: String,
        operations: List<PlannedOperation>,
        root: FileRef,
        folder: FileRef,
    ) {
        val mode = settingsRepository.storageAccessState.first().mode
        if (mode != StorageAccessMode.DIRECT) {
            _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
            return
        }
        val gateway = container.gatewayFor(mode)
        val children = withContext(Dispatchers.IO) { gateway.listChildren(folder) }
        val validated = PlanValidator.validate(operations, SingleFolderIndex(folder, children))
        val scope = ScanScope(root.displayScopeLabel(), root)
        _uiState.value = ScanUiState.PlanPreview(
            goal = goal,
            accepted = validated.accepted,
            rejected = validated.rejected,
            scopes = listOf(scope),
            acceptedScopeLabels = List(validated.accepted.size) { scope.label },
            unindexedFolder = folder,
        )
    }

    /**
     * Plan Section 8's cascade, run on demand rather than during every scan
     * — hashing file contents is exactly what Section 22/23 mean by content
     * inspection, which stays opt-in per action, not automatic.
     */
    fun findDuplicates(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Finding duplicates", "Grouping by size")
            try {
                val mode = summary.mode
                // SAF's openRead is still TODO(), so hashing would throw a raw
                // NotImplementedError rather than fail honestly. Guard here,
                // with the same message every other mutation-needing action
                // uses, instead of three different behaviors for one limit.
                if (mode == StorageAccessMode.SAF) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }
                val records = filesForScopes(summary.scopes)
                val detector = DuplicateDetector(container.gatewayFor(mode))
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
    fun proposeTrashDuplicates(review: ScanUiState.DuplicateReview) {
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

    fun exportInventory(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            try {
                if (summary.mode != StorageAccessMode.DIRECT ||
                    summary.scopes.any { it.root !is FileRef.Direct }
                ) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }

                val timestamp = System.currentTimeMillis()
                val written = mutableListOf<String>()
                val errors = mutableListOf<String>()
                val gateway = container.gatewayFor(StorageAccessMode.DIRECT)

                for ((scopeIndex, scope) in summary.scopes.withIndex()) {
                    val root = scope.root as FileRef.Direct
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
                                container.notifyExternalFileCreated(
                                    result.path,
                                    if (result.path.endsWith(".json")) "application/json" else "text/csv",
                                )
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

    fun analyzeImages(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            try {
                if (summary.mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }
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

    fun findSimilarFiles(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            try {
                if (summary.mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }
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
                            ImageDHash.fromPath(record.stableRef)
                        }
                        if (hash != null) {
                            signatures += SimilaritySignature(record.stableRef, SimilarityKind.IMAGE, hash)
                            imagesAnalyzed++
                        }
                    }
                }

                if (privacy.contentInspectionEnabled) {
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
                            signatures += SimilaritySignature(document.stableRef, SimilarityKind.DOCUMENT, hash)
                            documentsAnalyzed++
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
    fun findLargestFiles(summary: ScanUiState.Summary, limit: Int = 50) {
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

    fun enrichMetadata(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            try {
                val privacy = settingsRepository.privacySettings.first()
                if (!privacy.metadataIndexingEnabled) {
                    _uiState.value = ScanUiState.Error(
                        "Metadata indexing is off. Enable it in Settings before enriching file metadata.",
                    )
                    return@launch
                }
                if (summary.mode != StorageAccessMode.DIRECT) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
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
    fun findOldFiles(summary: ScanUiState.Summary, olderThanMonths: Int = 6) {
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

    /**
     * M9 natural-language entry. Parsing is deterministic and offline; the
     * result can only invoke read-only review or build ordinary typed plans.
     */
    private suspend fun runIndexedContentSearch(
        summary: ScanUiState.Summary,
        query: String,
        requestedCategories: Set<FileCategory> = emptySet(),
        sort: ContentSearchSort = ContentSearchSort.RELEVANCE,
        filters: ContentSearchFilters? = null,
        savedSearchId: String? = null,
    ) {
        if (summary.mode != StorageAccessMode.DIRECT) {
            _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
            return
        }
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
        container.startContentIndexing(roots)

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
            savedSearchId = savedSearchId,
        )
    }

    private fun watchIndexedSearch(
        query: String,
        roots: List<String>,
        savedSearchId: String?,
    ) {
        indexSearchWatchJob?.cancel()
        indexSearchWatchJob = viewModelScope.launch {
            val repository = container.contentIndexRepository(StorageAccessMode.DIRECT)
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

    fun setIndexedSearchSort(sort: ContentSearchSort) {
        val current = _review.value as? ScanUiState.IndexedContentSearchReview ?: return
        _review.value = current.copy(sort = sort)
    }

    fun setIndexedSearchFilters(filters: ContentSearchFilters) {
        val current = _review.value as? ScanUiState.IndexedContentSearchReview ?: return
        _review.value = current.copy(filters = filters)
    }

    fun resetIndexedSearchFilters() {
        val current = _review.value as? ScanUiState.IndexedContentSearchReview ?: return
        _review.value = current.copy(
            filters = ContentSearchFilters(
                categories = current.requestedCategories.mapTo(linkedSetOf()) { it.name },
            ),
        )
    }

    fun pauseContentIndexing() {
        container.pauseContentIndexing()
    }

    fun refreshIndexedSearch(review: ScanUiState.IndexedContentSearchReview) {
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

    fun handleNaturalLanguage(summary: ScanUiState.Summary, request: String) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Understanding request", "Offline deterministic parser")
            try {
                when (val parsed = DeterministicIntentParser.parse(request, lastBoundedIntent)) {
                    is IntentParseResult.Unsupported -> {
                        _uiState.value = ScanUiState.Error(parsed.reason)
                    }

                    is IntentParseResult.Parsed -> {
                        val intent = parsed.intent
                        lastBoundedIntent = intent
                        when (intent.action) {
                            IntentAction.DUPLICATE_REVIEW -> {
                                findDuplicates(summary)
                            }

                            IntentAction.FIND -> {
                                val records = applyIntentCriteria(
                                    filesForScopes(summary.scopes)
                                        .filter { record ->
                                            !record.isDirectory &&
                                                (intent.categories.isEmpty() ||
                                                    classifyByExtension(record.extension) in intent.categories)
                                        },
                                    intent,
                                )
                                val contentTerm = intent.contentTerm
                                if (contentTerm != null) {
                                    val contentFilters = ContentSearchFilters(
                                        categories = intent.categories.mapTo(linkedSetOf()) { it.name },
                                        minSizeBytes = intent.minSizeBytes,
                                        maxSizeBytes = intent.maxSizeBytes,
                                        modifiedAfter = intent.modifiedAfter,
                                        modifiedBefore = intent.modifiedBefore,
                                    )
                                    runIndexedContentSearch(
                                        summary = summary,
                                        query = contentTerm,
                                        requestedCategories = intent.categories,
                                        sort = intent.toContentSearchSort(),
                                        filters = contentFilters,
                                    )
                                } else {
                                    val matches = records.filter { record ->
                                        intent.findTerm == null ||
                                            record.displayName.contains(intent.findTerm, ignoreCase = true)
                                    }
                                    _uiState.value = ScanUiState.FileListReview(
                                        title = "Request: ${intent.rawRequest}",
                                        records = matches,
                                    )
                                }
                            }

                            IntentAction.RENAME -> {
                                if (summary.mode != StorageAccessMode.DIRECT) {
                                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                                    return@launch
                                }
                                val from = requireNotNull(intent.renameFrom)
                                val to = requireNotNull(intent.renameTo)
                                val matches = filesForScopes(summary.scopes).filter { record ->
                                    !record.isDirectory && record.displayName.equals(from, ignoreCase = true)
                                }
                                if (matches.isEmpty()) {
                                    _uiState.value = ScanUiState.Error("No file named \"$from\" was found in the selected scope.")
                                    return@launch
                                }
                                val operations = matches.map { record ->
                                    PlannedOperation.Rename(
                                        source = parseFileRef(record.stableRef),
                                        newName = to,
                                        reason = "Natural-language rename request",
                                    )
                                }
                                showPlanPreview(intent.rawRequest, operations, summary.scopes)
                            }

                            IntentAction.ORGANIZE,
                            IntentAction.GROUP,
                            IntentAction.ARCHIVE,
                            -> {
                                if (summary.mode != StorageAccessMode.DIRECT ||
                                    summary.scopes.any { it.root !is FileRef.Direct }
                                ) {
                                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                                    return@launch
                                }

                                val projectKeywords = settingsRepository.projectKeywords.first()
                                val generatedByScope = summary.scopes.map { scope ->
                                    val root = scope.root as FileRef.Direct
                                    val records = container.database.fileRecordDao()
                                        .getFilesUnderScopeRoot(root.rawValue())
                                    scope to IntentPlanGenerator.generate(
                                        scopeRoot = root,
                                        records = records,
                                        projectKeywords = projectKeywords,
                                        intent = intent,
                                    )
                                }
                                val operations = generatedByScope.flatMap { it.second.plan.operations }
                                if (operations.isEmpty()) {
                                    _uiState.value = ScanUiState.Error(
                                        "The request was understood, but there are no confidently matching files to move.",
                                    )
                                    return@launch
                                }
                                val notes = buildList {
                                    if (summary.scopes.size > 1) {
                                        add("Each selected scan root stays local; this request will not move files between roots.")
                                    }
                                    if (intent.action == IntentAction.ARCHIVE) {
                                        add("Archive means grouping files that are already archive formats; M9 does not create ZIP files.")
                                    }
                                    generatedByScope.forEach { (scope, generated) ->
                                        generated.scopeReport.previewLines().forEach { line ->
                                            add(if (summary.scopes.size == 1) line else "${scope.label}: $line")
                                        }
                                    }
                                }
                                showPlanPreview(intent.rawRequest, operations, summary.scopes, notes)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
    private fun applyIntentCriteria(
        records: List<FileRecord>,
        intent: BoundedIntent,
    ): List<FileRecord> {
        val filtered = records.filter { record ->
            if (intent.minSizeBytes != null && record.sizeBytes < intent.minSizeBytes) return@filter false
            if (intent.maxSizeBytes != null && record.sizeBytes > intent.maxSizeBytes) return@filter false
            val modified = record.modifiedAt
            if (intent.modifiedBefore != null && (modified == null || modified >= intent.modifiedBefore)) return@filter false
            if (intent.modifiedAfter != null && (modified == null || modified <= intent.modifiedAfter)) return@filter false
            true
        }
        val ordered = when (intent.order) {
            IntentOrder.DEFAULT -> filtered
            IntentOrder.LARGEST_FIRST -> filtered.sortedByDescending { it.sizeBytes }
            IntentOrder.SMALLEST_FIRST -> filtered.sortedBy { it.sizeBytes }
            IntentOrder.NEWEST_FIRST -> filtered.sortedByDescending { it.modifiedAt ?: Long.MIN_VALUE }
            IntentOrder.OLDEST_FIRST -> filtered.sortedBy { it.modifiedAt ?: Long.MAX_VALUE }
        }
        return intent.resultLimit?.let { ordered.take(it) } ?: ordered
    }

    private fun BoundedIntent.toContentSearchSort(): ContentSearchSort = when (order) {
        IntentOrder.DEFAULT -> ContentSearchSort.RELEVANCE
        IntentOrder.LARGEST_FIRST -> ContentSearchSort.LARGEST
        IntentOrder.SMALLEST_FIRST -> ContentSearchSort.SMALLEST
        IntentOrder.NEWEST_FIRST -> ContentSearchSort.MODIFIED_NEWEST
        IntentOrder.OLDEST_FIRST -> ContentSearchSort.MODIFIED_OLDEST
    }

    fun editPlanOperation(
        operationIndex: Int,
        newDestinationPath: String? = null,
        newName: String? = null,
    ) {
        val current = _preview.value ?: return
        if (operationIndex !in current.accepted.indices) return

        viewModelScope.launch {
            try {
                val original = current.accepted[operationIndex]
                val edited = when (original) {
                    is PlannedOperation.Move -> {
                        val path = newDestinationPath?.trim()?.trimEnd('/')
                        if (path.isNullOrBlank()) {
                            _error.value = "Move destination cannot be blank."
                            return@launch
                        }
                        original.copy(destination = FileRef.Direct(path))
                    }

                    is PlannedOperation.Rename -> {
                        val name = newName?.trim().orEmpty()
                        if (name.isBlank() || '/' in name || '\\' in name || name == "..") {
                            _error.value = "Rename target must be one safe file name."
                            return@launch
                        }
                        original.copy(newName = name)
                    }

                    else -> {
                        _error.value = "This operation type is not directly editable."
                        return@launch
                    }
                }

                val transformed = current.accepted.toMutableList().apply {
                    this[operationIndex] = edited
                }

                val sourceRoots = current.scopes
                    .mapNotNull { it.root as? FileRef.Direct }
                    .map { it.absolutePath.trimEnd('/') }

                val extraRoots = transformed
                    .flatMap { operation ->
                        when (operation) {
                            is PlannedOperation.CreateDirectory ->
                                listOfNotNull(operation.parent as? FileRef.Direct)
                            is PlannedOperation.Move -> listOfNotNull(
                                (operation.destination as? FileRef.Direct)
                                    ?.absolutePath
                                    ?.substringBeforeLast('/', missingDelimiterValue = "")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(FileRef::Direct),
                            )
                            is PlannedOperation.WriteTextFile ->
                                listOfNotNull(operation.parent as? FileRef.Direct)
                            is PlannedOperation.Rename,
                            is PlannedOperation.Trash,
                            -> emptyList()
                        }
                    }
                    .filterNot { candidate ->
                        sourceRoots.any { root ->
                            val path = candidate.absolutePath.trimEnd('/')
                            path == root || path.startsWith("$root/")
                        }
                    }
                    .distinctBy { it.absolutePath.trimEnd('/') }

                val index = buildAuthorizedPlanIndex(
                    scopes = current.scopes,
                    destinationRoots = extraRoots,
                    mode = StorageAccessMode.DIRECT,
                )
                val validated = PlanValidator.validate(transformed, index)
                if (validated.rejected.isNotEmpty() ||
                    validated.accepted.size != transformed.size
                ) {
                    _error.value = validated.rejected.firstOrNull()?.reason
                        ?: "The edited plan no longer validates against current storage."
                    return@launch
                }

                _preview.value = current.copy(
                    accepted = validated.accepted,
                    rejected = current.rejected,
                    acceptedScopeLabels = validated.accepted.map { operation ->
                        scopeForOperation(operation, current.scopes)?.label ?: "Approved destination"
                    },
                    selectedIndices = current.selectedIndices
                        .filterTo(linkedSetOf()) { it in validated.accepted.indices },
                    authorizedDestinationRoots = extraRoots,
                )
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            }
        }
    }

    fun editPlanDestinationGroup(
        groupDirectory: String,
        newDestinationRootPath: String? = null,
        newGroupName: String? = null,
        rememberForSimilarFiles: Boolean = false,
    ) {
        val current = _preview.value ?: return
        viewModelScope.launch {
            try {
                val oldDirectory = groupDirectory.trimEnd('/')
                val oldGroup = oldDirectory.substringAfterLast('/')
                val oldRoot = oldDirectory.substringBeforeLast('/', missingDelimiterValue = "")
                if (oldRoot.isBlank()) {
                    _error.value = "Cannot determine the current destination root."
                    return@launch
                }

                val targetRoot = newDestinationRootPath
                    ?.trim()
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: oldRoot
                val targetGroup = if (newGroupName == null) {
                    oldGroup
                } else {
                    SemanticPlanAdapter.sanitizeGroup(newGroupName)
                        ?: run {
                            _error.value = "Group name is blank or contains an unsafe path separator."
                            return@launch
                        }
                }

                val targetRootRef = FileRef.Direct(targetRoot)
                val gateway = container.gatewayFor(StorageAccessMode.DIRECT)
                val targetExists = withContext(Dispatchers.IO) {
                    gateway.exists(targetRootRef) &&
                        runCatching { gateway.stat(targetRootRef).isDirectory }.getOrDefault(false)
                }
                if (!targetExists) {
                    _error.value = "The selected destination root does not exist."
                    return@launch
                }

                val affectedSourceNames = current.accepted
                    .filterIsInstance<PlannedOperation.Move>()
                    .filter { move ->
                        (move.destination as? FileRef.Direct)
                            ?.absolutePath
                            ?.substringBeforeLast('/', missingDelimiterValue = "") == oldDirectory
                    }
                    .mapNotNull { move ->
                        (move.source as? FileRef.Direct)?.absolutePath?.substringAfterLast('/')
                    }

                val transformed = current.accepted.map { operation ->
                    when (operation) {
                        is PlannedOperation.CreateDirectory -> {
                            val candidate = (operation.parent as? FileRef.Direct)
                                ?.let { parent -> "${parent.absolutePath.trimEnd('/')}/${operation.name}" }
                            if (candidate == oldDirectory) {
                                operation.copy(parent = targetRootRef, name = targetGroup)
                            } else {
                                operation
                            }
                        }

                        is PlannedOperation.Move -> {
                            val destination = operation.destination as? FileRef.Direct
                            val destinationParent = destination?.absolutePath
                                ?.substringBeforeLast('/', missingDelimiterValue = "")
                            if (destination != null && destinationParent == oldDirectory) {
                                operation.copy(
                                    destination = FileRef.Direct(
                                        "$targetRoot/$targetGroup/${destination.absolutePath.substringAfterLast('/')}",
                                    ),
                                )
                            } else {
                                operation
                            }
                        }

                        else -> operation
                    }
                }

                val sourceRoots = current.scopes
                    .mapNotNull { it.root as? FileRef.Direct }
                    .map { it.absolutePath.trimEnd('/') }
                val extraRoots = transformed
                    .filterIsInstance<PlannedOperation.CreateDirectory>()
                    .mapNotNull { it.parent as? FileRef.Direct }
                    .filterNot { parent ->
                        parent.absolutePath.trimEnd('/') in sourceRoots
                    }
                    .distinctBy { it.absolutePath.trimEnd('/') }

                val index = buildAuthorizedPlanIndex(
                    scopes = current.scopes,
                    destinationRoots = extraRoots,
                    mode = StorageAccessMode.DIRECT,
                )
                val validated = PlanValidator.validate(transformed, index)
                _preview.value = current.copy(
                    accepted = validated.accepted,
                    rejected = current.rejected + validated.rejected,
                    acceptedScopeLabels = validated.accepted.map { operation ->
                        scopeForOperation(operation, current.scopes)?.label ?: "Approved destination"
                    },
                    selectedIndices = PlanSelection.allSelected(validated.accepted),
                    authorizedDestinationRoots = extraRoots,
                )
                if (rememberForSimilarFiles) {
                    learnableFilenameTerm(affectedSourceNames)?.let { term ->
                        settingsRepository.addCorrectionRule(term, targetGroup)
                    }
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            }
        }
    }

    private fun learnableFilenameTerm(names: List<String>): String? {
        if (names.isEmpty()) return null
        val generic = setOf(
            "final", "copy", "file", "document", "download", "notes", "note",
            "image", "img", "screenshot", "scan", "new",
        )
        val tokenSets = names.map { name ->
            name.substringBeforeLast('.', name)
                .lowercase()
                .split(Regex("""[^a-z0-9]+"""))
                .filter { token ->
                    token.length >= 3 &&
                        token !in generic &&
                        token.any { it.isLetter() }
                }
                .toSet()
        }
        val minimum = if (tokenSets.size == 1) 1 else (tokenSets.size + 1) / 2
        return tokenSets
            .flatten()
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= minimum }
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
            ?.key
    }

    fun setPlanOperationSelected(index: Int, selected: Boolean) {
        val current = _preview.value ?: return
        _preview.value = current.copy(
            selectedIndices = PlanSelection.setSelected(
                operations = current.accepted,
                current = current.selectedIndices,
                index = index,
                selected = selected,
            ),
        )
    }

    fun approvePlan(preview: ScanUiState.PlanPreview) {
        viewModelScope.launch {
            val selectedOperations = PlanSelection.selectedOperations(
                preview.accepted,
                preview.selectedIndices,
            )
            if (selectedOperations.isEmpty()) {
                _uiState.value = ScanUiState.Error("Select at least one action to run.")
                return@launch
            }

            _uiState.value = ScanUiState.Working(
                label = "Starting task",
                detail = "Saving the approved changes before execution",
                processed = 0,
                total = selectedOperations.size,
            )

            var queuedTaskRunId: Long? = null
            try {
                val accessState = settingsRepository.storageAccessState.first()
                val mode = accessState.mode ?: run {
                    _uiState.value = ScanUiState.Error("No storage access granted yet.")
                    return@launch
                }
                val executor = container.planExecutor(mode)
                val plan = AgentPlan(preview.goal, selectedOperations)
                val unindexed = preview.unindexedFolder
                val index = if (unindexed == null) {
                    buildAuthorizedPlanIndex(
                        scopes = preview.scopes,
                        destinationRoots = preview.authorizedDestinationRoots,
                        mode = mode,
                    )
                } else {
                    SingleFolderIndex(
                        unindexed,
                        withContext(Dispatchers.IO) {
                            container.gatewayFor(mode).listChildren(unindexed)
                        },
                    )
                }

                val taskRunId = withContext(Dispatchers.IO) {
                    executor.enqueueApproved(
                        plan = plan,
                        scopeRootRef = preview.scopeRoot.rawValue(),
                        storageAccessMode = mode,
                        index = index,
                    )
                }
                queuedTaskRunId = taskRunId

                // The service receives only the durable task id. It cannot
                // alter the approved plan or bypass the validator/preview.
                container.startForegroundTask(taskRunId)

                _uiState.value = ScanUiState.ExecutionQueued(
                    taskRunId = taskRunId,
                    operationCount = selectedOperations.size,
                )
            } catch (t: Throwable) {
                queuedTaskRunId?.let { id ->
                    withContext(Dispatchers.IO) {
                        val dao = container.database.taskRunDao()
                        dao.getById(id)?.let { task ->
                            dao.update(
                                task.copy(
                                    status = com.pocketsteward.app.data.db.TaskRunStatus.CANCELLED,
                                    completedAt = System.currentTimeMillis(),
                                    summary = "Queued safely, but foreground execution did not start. Resume from Tasks.",
                                ),
                            )
                        }
                    }
                }
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun undoTask(taskRunId: Long) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Undoing(taskRunId)
            try {
                val summary = withContext(Dispatchers.IO) {
                    container.undoExecutor.undo(taskRunId) { completed, total ->
                        _uiState.value = ScanUiState.Undoing(taskRunId, completed, total)
                    }
                }
                _uiState.value = ScanUiState.UndoDone(summary)
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Starting over: a genuinely new scan, not a back press.
     *
     * This is what `reset()` was always written for. Until M7 it was also
     * wired to every review screen's back button, which is why leaving a
     * review threw the scan away.
     */
    fun reset() {
        autoStarted = false
        _summary.value = null
        _scanning.value = null
        _busy.value = null
        _review.value = null
        _preview.value = null
        _completion.value = null
        _undoProgress.value = null
        _picker.value = null
        _error.value = null
        _uiState.value = ScanUiState.Idle
    }

    private companion object {
        /**
         * One message for one limitation. SAF mode used to produce three
         * different outcomes for the same underlying gap — a clean guard
         * message from the planners, a raw NotImplementedError from anything
         * that opened a file, and silent success elsewhere. SAF mutations and
         * content reads stay deliberately unimplemented (plan Section 5 /
         * STATUS.md), so every path that needs them says the same thing.
         */
        const val SAF_UNSUPPORTED =
            "This needs full file-manager access. In folder-only (SAF) mode Pocket Steward can scan and " +
                "browse, but it can't move, trash, or read file contents. Change storage access in Settings."

        const val COHERENCE_EXCERPT_CHARS = 1_800
        const val COHERENCE_BATCH_SIZE = 12
        const val MAX_SIMILARITY_FILES_PER_KIND = 1_000
        const val MAX_SIMHASH_TEXT_CHARS = 100_000
        const val MAX_IMAGE_ANALYSIS_FILES = 250
    }

    private suspend fun resolveScopes(
        targets: List<ScanTarget>,
        mode: StorageAccessMode,
        safTreeUri: String?,
        gateway: StorageGateway,
    ): List<ScanScope> {
        val candidates = targets
            .distinctBy { it.selectionKey() }
            .map { target -> ScanScope(target.label, resolveRoot(target, mode, safTreeUri, gateway)) }

        if (mode == StorageAccessMode.SAF) {
            return candidates.take(1)
        }

        val normalizedPaths = ScanRootSet.normalize(candidates.map { it.root.rawValue() })
        return normalizedPaths.map { normalized ->
            candidates.first { it.root.rawValue().trimEnd('/') == normalized.trimEnd('/') }
        }
    }

    private suspend fun filesForScopes(scopes: List<ScanScope>): List<FileRecord> =
        scopes.flatMap { scope ->
            container.database.fileRecordDao().getFilesUnderScopeRoot(scope.root.rawValue())
        }.distinctBy { it.stableRef }

    private suspend fun allRecordsForScopes(scopes: List<ScanScope>): List<FileRecord> =
        scopes.flatMap { scope ->
            container.database.fileRecordDao().getAllUnderScopeRoot(scope.root.rawValue())
        }.distinctBy { it.stableRef }

    private suspend fun resolveRoot(
        target: ScanTarget,
        mode: StorageAccessMode,
        safTreeUri: String?,
        gateway: StorageGateway,
    ): FileRef {
        if (mode == StorageAccessMode.SAF) {
            val uri = requireNotNull(safTreeUri) { "SAF mode with no granted tree URI" }
            // Must go through rootOf(), not a bare FileRef.Saf(uri): it
            // normalizes the raw tree URI (".../tree/X") into document-URI
            // form (".../tree/X/document/X"), which is what makes
            // SafStorageGateway's fromTreeUri-based listing/stat resolve
            // this node instead of misbehaving on an un-normalized ref.
            return gateway.rootOf(StorageScope.Tree(FileRef.Saf(uri), "Granted folder"))
        }
        // Environment.getExternalStoragePublicDirectory is deprecated for
        // scoped-storage apps in general, but this app deliberately runs
        // under MANAGE_EXTERNAL_STORAGE (plan Section 5, Mode A), which is
        // exactly the case that API still serves correctly.
        @Suppress("DEPRECATION")
        return when (target) {
            ScanTarget.Downloads ->
                FileRef.Direct(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
            ScanTarget.Documents ->
                FileRef.Direct(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath)
            ScanTarget.Pictures ->
                FileRef.Direct(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath)
            ScanTarget.Everything ->
                FileRef.Direct(Environment.getExternalStorageDirectory().absolutePath)
            is ScanTarget.CustomFolder -> FileRef.Direct(target.absolutePath)
            is ScanTarget.GrantedFolder ->
                error("GrantedFolder target is only valid in SAF mode")
        }
    }
}

private fun sourceRootFor(stableRef: String, scopes: List<ScanScope>): String? =
    scopes
        .map { it.root.rawValue().trimEnd('/') }
        .filter { root -> stableRef == root || stableRef.startsWith("$root/") }
        .maxByOrNull { it.length }

private fun scopeForOperation(operation: PlannedOperation, scopes: List<ScanScope>): ScanScope? {
    val anchor = when (operation) {
        is PlannedOperation.CreateDirectory -> operation.parent
        is PlannedOperation.Move -> operation.source
        is PlannedOperation.Rename -> operation.source
        is PlannedOperation.Trash -> operation.source
        is PlannedOperation.WriteTextFile -> operation.parent
    }
    val raw = anchor.rawValue().trimEnd('/')
    return scopes.firstOrNull { scope ->
        val root = scope.root.rawValue().trimEnd('/')
        raw == root || raw.startsWith("$root/")
    }
}

private fun FileRef.displayScopeLabel(): String = when (this) {
    is FileRef.Direct -> absolutePath.trimEnd('/').substringAfterLast('/').ifBlank { absolutePath }
    is FileRef.Saf -> "Granted folder"
}

private fun ScanTarget.selectionKey(): String = when (this) {
    ScanTarget.Downloads -> "preset:downloads"
    ScanTarget.Documents -> "preset:documents"
    ScanTarget.Pictures -> "preset:pictures"
    ScanTarget.Everything -> "preset:everything"
    is ScanTarget.GrantedFolder -> "saf:$label"
    is ScanTarget.CustomFolder -> "path:${absolutePath.trimEnd('/')}"
}

private fun FileRecord.toSortCandidate(): SortCandidate = SortCandidate(
    stableRef = stableRef,
    parentRef = parentRef,
    displayName = displayName,
    isDirectory = isDirectory,
)

/**
 * The parent of this folder, or null when it is [storageRoot] or somehow
 * outside it. Keeps "up" from walking off the top of what the app can read.
 */
private fun FileRef.Direct.parentWithin(storageRoot: FileRef.Direct): FileRef.Direct? {
    val rootPath = storageRoot.absolutePath.trimEnd('/')
    val here = absolutePath.trimEnd('/')
    if (here == rootPath || !here.startsWith("$rootPath/")) return null
    val parentPath = here.substringBeforeLast('/', missingDelimiterValue = "")
    return if (parentPath.isBlank()) null else FileRef.Direct(parentPath)
}

/** The protection marker's path inside this folder. */
private fun FileRef.Direct.markerRef(): FileRef.Direct =
    FileRef.Direct("${absolutePath.trimEnd('/')}/$DO_NOT_SORT_MARKER")
