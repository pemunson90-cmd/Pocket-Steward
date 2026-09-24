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
    val ref: FileRef,
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
    val markerStableRef: String? = null,
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
        val largeFileCount: Int = 0,
        val uncategorizedCount: Int = 0,
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
        val selectedIndices: Set<Int> = PlanSelection.safeSelected(accepted),
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
    /** Files that are versions of one another, by name. Newest stays; older ones can be moved aside. */
    data class VersionChainReview(
        val chains: List<com.pocketsteward.app.versions.VersionChains.Chain>,
        val scopes: List<ScanScope>,
    ) : ScanUiState {
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
    data class FileListReview(
        val title: String,
        val records: List<FileRecord>,
        val explanationByRef: Map<String, String> = emptyMap(),
    ) : ScanUiState
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
        val root: FileRef,
        val current: FileRef,
        val currentDisplayName: String,
        val currentIsProtected: Boolean,
        val children: List<BrowsableFolder>,
        val ancestors: List<FileRef> = emptyList(),
    ) : ScanUiState {
        val parent: FileRef? get() = ancestors.lastOrNull()
    }

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
    internal val settingsRepository: SettingsRepository,
    internal val container: AppContainer,
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
    internal val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    /**
     * The scan result, and the only state here that is expensive to rebuild.
     * Survives every review, preview and completion screen. Cleared only by
     * [reset] or by starting another scan.
     */
    internal val _summary = MutableStateFlow<ScanUiState.Summary?>(null)
    val summary: StateFlow<ScanUiState.Summary?> = _summary

    private val _scanning = MutableStateFlow<ScanProgress?>(null)
    val scanning: StateFlow<ScanProgress?> = _scanning

    /** Long operations that are not a scan: hashing, planning, executing. */
    private val _busy = MutableStateFlow<ScanUiState.Working?>(null)
    val busy: StateFlow<ScanUiState.Working?> = _busy

    internal val _review = MutableStateFlow<ScanUiState?>(null)
    val review: StateFlow<ScanUiState?> = _review

    internal val _preview = MutableStateFlow<ScanUiState.PlanPreview?>(null)
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
    internal val _error = MutableStateFlow<String?>(null)
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

    val taskRuns: StateFlow<List<TaskRun>> =
        container.database.taskRunDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val taskProgress: StateFlow<Map<Long, TaskJournalProgress>> =
        container.database.mutationRecordDao().observeTaskProgress()
            .map { rows -> rows.associateBy(TaskJournalProgress::taskRunId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    internal val _selectedTargets = MutableStateFlow<List<ScanTarget>>(emptyList())
    val selectedTargets: StateFlow<List<ScanTarget>> = _selectedTargets

    /** Guards against a recomposition re-triggering a Home tile's auto-scan. */
    internal var autoStarted = false

    private var scanJob: Job? = null
    internal var indexSearchWatchJob: Job? = null
    internal var lastBoundedIntent: BoundedIntent? = null
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
            is ScanUiState.VersionChainReview,
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
     * Rebuild the most recent completed scan from Room without walking the
     * filesystem again. Android may recreate the Results destination after
     * killing the graph-scoped ViewModel; the indexed inventory is durable,
     * so an empty screen should not force a 20k-file rescan.
     */
    fun restoreLastScanSummaryIfAvailable(navigateToResults: Boolean = false) {
        if (_summary.value != null || scanJob?.isActive == true) {
            if (navigateToResults && _summary.value != null) {
                viewModelScope.launch { navChannel.send(ScanRoute.RESULTS) }
            }
            return
        }

        viewModelScope.launch {
            try {
                val session = settingsRepository.lastScanSession.first() ?: return@launch
                val access = settingsRepository.storageAccessState.first()
                if (access.mode != session.mode) {
                    settingsRepository.clearLastScanSession()
                    return@launch
                }

                val scopes = session.roots.map { saved ->
                    ScanScope(
                        label = saved.label,
                        root = parseFileRef(saved.rawRef),
                    )
                }
                val allRootsStillIndexed = scopes.all { scope ->
                    container.database.fileRecordDao()
                        .getByStableRef(scope.root.rawValue()) != null
                }
                if (!allRootsStillIndexed) {
                    settingsRepository.clearLastScanSession()
                    return@launch
                }

                val records = filesForScopes(scopes)
                val projectKeywords = settingsRepository.projectKeywords.first()
                val byCategory = records
                    .groupBy { classifyByExtension(it.extension) }
                    .mapValues { (_, files) ->
                        CategoryStat(
                            fileCount = files.size,
                            totalBytes = files.sumOf { it.sizeBytes },
                        )
                    }

                val restored = ScanUiState.Summary(
                    scopes = scopes,
                    mode = session.mode,
                    totalFiles = records.size,
                    totalBytes = records.sumOf { it.sizeBytes },
                    byCategory = byCategory,
                    largeFileCount = records.count {
                        !it.isDirectory && it.sizeBytes >= LARGE_FILE_SUMMARY_BYTES
                    },
                    uncategorizedCount = records.count {
                        !it.isDirectory &&
                            RuleEngine.classify(
                                it.displayName,
                                it.extension,
                                projectKeywords,
                            ).isUncategorized()
                    },
                )
                if (navigateToResults) {
                    _uiState.value = restored
                } else {
                    _summary.value = restored
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Could not restore the previous scan."
            }
        }
    }

    fun startLastScanSession() {
        if (autoStarted) return
        autoStarted = true
        restoreLastScanSummaryIfAvailable(navigateToResults = true)
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


    fun toggleScanTarget(target: ScanTarget) {
        val key = target.selectionKey()
        val current = _selectedTargets.value
        if (target is ScanTarget.GrantedFolder || target is ScanTarget.GrantedSubfolder) {
            _selectedTargets.value =
                if (current.any { it.selectionKey() == key }) emptyList() else listOf(target)
            return
        }
        _selectedTargets.value = if (current.any { it.selectionKey() == key }) {
            current.filterNot { it.selectionKey() == key }
        } else {
            current + target
        }
    }

    fun addBrowsedFolderToSelection(folder: FileRef) {
        val target = when (folder) {
            is FileRef.Direct -> ScanTarget.CustomFolder(folder.absolutePath)
            is FileRef.Saf -> ScanTarget.GrantedSubfolder(
                documentUri = folder.documentUri,
                label = folder.displayScopeLabel(),
            )
            is FileRef.Child -> return
        }
        if (target is ScanTarget.GrantedSubfolder) {
            _selectedTargets.value = listOf(target)
        } else if (_selectedTargets.value.none { it.selectionKey() == target.selectionKey() }) {
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
        thenScheduledSuggestion: PendingCleanupSuggestion? = null,
    ) {
        startScan(
            targets = listOf(target),
            thenRun = thenRun,
            thenRequest = thenRequest,
            thenSavedSearch = thenSavedSearch,
            thenImportedPlan = thenImportedPlan,
            thenScheduledSuggestion = thenScheduledSuggestion,
        )
    }

    fun startScan(
        targets: Collection<ScanTarget>,
        thenRun: PostScanAction? = null,
        thenRequest: String? = null,
        thenSavedSearch: SavedSearch? = null,
        thenImportedPlan: DurablePlan? = null,
        thenScheduledSuggestion: PendingCleanupSuggestion? = null,
        forceWalk: Boolean = false,
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
                    // Background library: a recent whole-storage inventory
                    // already holds this folder, so its slice becomes this
                    // scan instead of walking the disk again. "Refresh this
                    // scan" passes forceWalk to always read storage live.
                    if (!forceWalk) {
                        val adopted = withContext(Dispatchers.IO) {
                            runCatching { container.library.tryAdopt(scope.root, accessState) }.getOrDefault(false)
                        }
                        if (adopted) continue
                    }
                    var thisRootProcessed = 0
                    withContext(Dispatchers.IO) {
                        container.scanLocks.withScanLock(scope.root.rawValue()) {
                            // Waited behind the background library? It may have just done this walk.
                            if (!forceWalk && runCatching { container.library.tryAdopt(scope.root, accessState) }.getOrDefault(false)) {
                                return@withScanLock
                            }
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
                    }
                    completedBeforeThisRoot += thisRootProcessed
                    if (withContext(Dispatchers.IO) { container.library.isLibraryRoot(scope.root, accessState) }) {
                        container.library.noteCompleted(scope.root.rawValue())
                    }
                }

                val records = filesForScopes(scopes)
                val byCategory = records
                    .groupBy { classifyByExtension(it.extension) }
                    .mapValues { (_, files) ->
                        CategoryStat(fileCount = files.size, totalBytes = files.sumOf { it.sizeBytes })
                    }

                val projectKeywords = settingsRepository.projectKeywords.first()
                val summary = ScanUiState.Summary(
                    scopes = scopes,
                    mode = mode,
                    totalFiles = records.size,
                    totalBytes = records.sumOf { it.sizeBytes },
                    byCategory = byCategory,
                    largeFileCount = records.count {
                        !it.isDirectory && it.sizeBytes >= LARGE_FILE_SUMMARY_BYTES
                    },
                    uncategorizedCount = records.count {
                        !it.isDirectory &&
                            RuleEngine.classify(
                                it.displayName,
                                it.extension,
                                projectKeywords,
                            ).isUncategorized()
                    },
                )
                settingsRepository.setLastScanSession(
                    LastScanSession(
                        mode = mode,
                        roots = scopes.map { scope ->
                            LastScanRoot(
                                label = scope.label,
                                rawRef = scope.root.rawValue(),
                            )
                        },
                        savedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                _uiState.value = summary

                targets.forEach { target ->
                    when (target) {
                        is ScanTarget.CustomFolder ->
                            settingsRepository.rememberRecentFolder(target.absolutePath)
                        is ScanTarget.GrantedSubfolder ->
                            settingsRepository.rememberRecentFolder(target.documentUri)
                        else -> Unit
                    }
                }

                if (thenScheduledSuggestion != null) {
                    if (thenScheduledSuggestion.newFileRefs.isNotEmpty()) {
                        proposeScheduledCleanup(
                            summary = summary,
                            suggestion = thenScheduledSuggestion,
                        )
                    } else {
                        // Backward-compatible fallback for v1 pending
                        // suggestions that predate exact new-file refs.
                        proposeSmartCleanup(summary)
                    }
                } else {
                    when (thenRun) {
                        null -> Unit
                        PostScanAction.SMART_CLEANUP -> proposeSmartCleanup(summary)
                        PostScanAction.FIND_DUPLICATES -> findDuplicates(summary)
                        PostScanAction.FIND_LARGEST -> findLargestFiles(summary)
                        PostScanAction.FIND_OLD -> findOldFiles(summary)
                        PostScanAction.REVIEW_UNCATEGORIZED -> findUncategorized(summary)
                    }
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
                    val sourceRoots = summary.scopes
                        .mapNotNull { (it.root as? FileRef.Direct)?.absolutePath?.trimEnd('/') }
                    val destinationRoots = authorizedRootsForImportedPlan(
                        operations = imported.operations,
                        sourceRoots = sourceRoots,
                    )

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

    fun refreshScan(summary: ScanUiState.Summary) {
        val targets = when (summary.mode) {
            StorageAccessMode.DIRECT -> summary.scopes.mapNotNull { scope ->
                (scope.root as? FileRef.Direct)?.let { ScanTarget.CustomFolder(it.absolutePath) }
            }
            StorageAccessMode.SAF -> listOf(ScanTarget.GrantedFolder(summary.scopeLabel))
        }

        if (targets.isEmpty()) {
            _error.value = "The current scan no longer has a reusable storage scope."
            return
        }
        _selectedTargets.value = targets
        startScan(targets, forceWalk = true)
    }

    /**
     * Plan Section 4/9: the rule engine plans, no model involved. Pulls
     * [SettingsRepository.projectKeywords] so a user-configured term like
     * "Leaseworld" groups by project before falling back to a plain
     * extension category, then runs the result through the same
     * [PlanValidator] every other plan goes through — nothing about being
     * rule-generated exempts it from validation.
     */

    fun proposeSmartCleanup(summary: ScanUiState.Summary, includeSubfolders: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Planning cleanup", "Classifying files under ${summary.scopeLabel}")
            try {
                val projectKeywords = settingsRepository.projectKeywords.first()
                val generatedByScope = summary.scopes.map { scope ->
                    val root = scope.root
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
     * The single path a plan takes to the screen, whatever produced it:
     * validate, then preview. Nothing calls `PlanExecutor` without passing
     * through here first, which is what keeps plan Decision 1 true when a
     * second [com.pocketsteward.app.cleanup.PlanSource] (the AI one) arrives.
     */
    internal suspend fun showPlanPreview(
        goal: String,
        operations: List<PlannedOperation>,
        scopes: List<ScanScope>,
        scopeNotes: List<String> = emptyList(),
        authorizedDestinationRoots: List<FileRef.Direct> = emptyList(),
    ) {
        val mode = settingsRepository.storageAccessState.first().mode
            ?: error("No storage access mode is active.")
        val index = buildAuthorizedPlanIndex(
            scopes = scopes,
            destinationRoots = authorizedDestinationRoots,
            mode = mode,
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

    internal suspend fun buildAuthorizedPlanIndex(
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

    internal suspend fun showPlanPreview(
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
    internal suspend fun showUnindexedPlanPreview(
        goal: String,
        operations: List<PlannedOperation>,
        root: FileRef,
        folder: FileRef,
    ) {
        val mode = settingsRepository.storageAccessState.first().mode
            ?: run {
                _uiState.value = ScanUiState.Error("No storage access is active.")
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

    fun pauseTaskExecution() {
        runCatching { container.pauseForegroundTask() }
            .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
    }

    fun resumeTaskExecution(taskRunId: Long) {
        runCatching { container.startForegroundTask(taskRunId) }
            .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
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

    internal companion object {
        /**
         * One message for one limitation. SAF mode used to produce three
         * different outcomes for the same underlying gap — a clean guard
         * message from the planners, a raw NotImplementedError from backend
         * stubs, and silent success elsewhere. SAF can now scan, browse, hash,
         * inspect text, label images, and run read-only audits. Mutations remain
         * deliberately fenced, so every path that changes files says the same thing.
         */
        const val SAF_UNSUPPORTED =
            "This action needs a filesystem path outside the selected Android document tree. " +
                "Within the granted tree Pocket Steward can now organize, rename, copy, move, quarantine, undo, hash, and inspect files."

        const val COHERENCE_EXCERPT_CHARS = 1_800
        const val COHERENCE_BATCH_SIZE = 12
        const val MAX_SIMILARITY_FILES_PER_KIND = 1_000
        const val MAX_SIMHASH_TEXT_CHARS = 100_000
        const val MAX_IMAGE_ANALYSIS_FILES = 250
        const val LARGE_FILE_SUMMARY_BYTES = 500L * 1024L * 1024L
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

    internal suspend fun filesForScopes(scopes: List<ScanScope>): List<FileRecord> =
        scopes.flatMap { scope ->
            container.database.fileRecordDao().getFilesUnderScopeRoot(scope.root.rawValue())
        }.distinctBy { it.stableRef }

    internal suspend fun allRecordsForScopes(scopes: List<ScanScope>): List<FileRecord> =
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
            return when (target) {
                is ScanTarget.GrantedSubfolder -> FileRef.Saf(target.documentUri)
                else -> {
                    val uri = requireNotNull(safTreeUri) { "SAF mode with no granted tree URI" }
                    gateway.rootOf(StorageScope.Tree(FileRef.Saf(uri), "Granted folder"))
                }
            }
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
            is ScanTarget.GrantedFolder,
            is ScanTarget.GrantedSubfolder,
            -> error("SAF target is only valid in SAF mode")
        }
    }
}

internal fun sourceRootFor(stableRef: String, scopes: List<ScanScope>): String? =
    scopes
        .map { it.root.rawValue().trimEnd('/') }
        .filter { root -> stableRef == root || stableRef.startsWith("$root/") }
        .maxByOrNull { it.length }

internal fun scopeForOperation(operation: PlannedOperation, scopes: List<ScanScope>): ScanScope? {
    val anchor = when (operation) {
        is PlannedOperation.CreateDirectory -> operation.parent
        is PlannedOperation.Move -> operation.source
        is PlannedOperation.Copy -> operation.source
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

internal fun FileRef.displayScopeLabel(): String = when (this) {
    is FileRef.Direct -> absolutePath.trimEnd('/').substringAfterLast('/').ifBlank { absolutePath }
    is FileRef.Saf -> documentUri.substringAfterLast('/').ifBlank { "Granted folder" }
    is FileRef.Child -> name
}

private fun ScanTarget.selectionKey(): String = when (this) {
    ScanTarget.Downloads -> "preset:downloads"
    ScanTarget.Documents -> "preset:documents"
    ScanTarget.Pictures -> "preset:pictures"
    ScanTarget.Everything -> "preset:everything"
    is ScanTarget.GrantedFolder -> "saf-root:$label"
    is ScanTarget.GrantedSubfolder -> "saf-sub:$documentUri"
    is ScanTarget.CustomFolder -> "path:${absolutePath.trimEnd('/')}"
}

internal fun FileRecord.toSortCandidate(): SortCandidate = SortCandidate(
    stableRef = stableRef,
    parentRef = parentRef,
    displayName = displayName,
    isDirectory = isDirectory,
)

/**
 * The parent of this folder, or null when it is [storageRoot] or somehow
 * outside it. Keeps "up" from walking off the top of what the app can read.
 */
internal fun FileRef.Direct.directAncestorsWithin(storageRoot: FileRef.Direct): List<FileRef> {
    val rootPath = storageRoot.absolutePath.trimEnd('/')
    var here = absolutePath.trimEnd('/')
    if (here == rootPath || !here.startsWith("$rootPath/")) return emptyList()

    val reversed = mutableListOf<FileRef>()
    while (here != rootPath) {
        val parentPath = here.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isBlank() || parentPath.length < rootPath.length) break
        reversed += FileRef.Direct(parentPath)
        here = parentPath
    }
    return reversed.asReversed()
}
