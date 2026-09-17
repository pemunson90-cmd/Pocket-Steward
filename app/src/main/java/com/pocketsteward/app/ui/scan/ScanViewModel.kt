package com.pocketsteward.app.ui.scan

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.cleanup.PlanRequest
import com.pocketsteward.app.cleanup.RuleBasedPlanSource
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.dedupe.DuplicateDetector
import com.pocketsteward.app.dedupe.DuplicateGroup
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.executor.ExecutionSummary
import com.pocketsteward.app.executor.UndoSummary
import com.pocketsteward.app.executor.InMemoryFileIndex
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.rules.RuleEngine
import com.pocketsteward.app.rules.isUncategorized
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.scan.ScanPhase
import com.pocketsteward.app.scan.ScanProgress
import com.pocketsteward.app.scan.classifyByExtension
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class CategoryStat(val fileCount: Int, val totalBytes: Long)

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val progress: ScanProgress) : ScanUiState
    data class Summary(
        val scopeLabel: String,
        val scopeRoot: FileRef,
        val mode: StorageAccessMode,
        val totalFiles: Int,
        val totalBytes: Long,
        val byCategory: Map<FileCategory, CategoryStat>,
    ) : ScanUiState
    data class PlanPreview(
        val goal: String,
        val accepted: List<PlannedOperation>,
        val rejected: List<RejectedOperation>,
        val scopeRoot: FileRef,
    ) : ScanUiState
    data class ExecutionDone(val summary: ExecutionSummary) : ScanUiState
    data class Undoing(val taskRunId: Long) : ScanUiState
    data class UndoDone(val summary: UndoSummary) : ScanUiState
    /** Plan Section 8: duplicate candidates found by the size/fingerprint/hash cascade, not yet acted on. */
    data class DuplicateReview(val groups: List<DuplicateGroup>, val scopeRoot: FileRef, val scopeLabel: String) : ScanUiState
    /** Plan Section 16's "find large files" / "find old files" quick actions: browse only, no plan generated. */
    data class FileListReview(val title: String, val records: List<FileRecord>) : ScanUiState

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

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState

    /** Guards against a recomposition re-triggering a Home tile's auto-scan. */
    private var autoStarted = false

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

    fun startScan(target: ScanTarget, thenRun: PostScanAction? = null) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Scanning(ScanProgress(0, null, ScanPhase.SCANNING))
            try {
                val accessState = settingsRepository.storageAccessState.first()
                val mode = accessState.mode
                if (mode == null) {
                    _uiState.value = ScanUiState.Error("No storage access granted yet.")
                    return@launch
                }

                val gateway = container.gatewayFor(mode)
                val root = resolveRoot(target, mode, accessState.safTreeUri, gateway)
                val scanner = container.fileScanner(mode)

                // DirectStorageGateway's listChildren/stat are suspend
                // functions doing blocking java.io.File work with no
                // dispatcher of their own — without this, that I/O runs on
                // Dispatchers.Main.immediate (viewModelScope's default) and
                // a scan of "Everything" would freeze the UI thread long
                // enough to ANR.
                withContext(Dispatchers.IO) {
                    scanner.scan(root) { progress ->
                        _uiState.value = ScanUiState.Scanning(progress)
                    }
                }

                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
                val byCategory = records
                    .groupBy { classifyByExtension(it.extension) }
                    .mapValues { (_, files) ->
                        CategoryStat(fileCount = files.size, totalBytes = files.sumOf { it.sizeBytes })
                    }

                val summary = ScanUiState.Summary(
                    scopeLabel = target.label,
                    scopeRoot = root,
                    mode = mode,
                    totalFiles = records.size,
                    totalBytes = records.sumOf { it.sizeBytes },
                    byCategory = byCategory,
                )
                _uiState.value = summary

                when (thenRun) {
                    null -> Unit
                    PostScanAction.SMART_CLEANUP -> proposeSmartCleanup(summary)
                    PostScanAction.FIND_DUPLICATES -> findDuplicates(summary)
                    PostScanAction.FIND_LARGEST -> findLargestFiles(summary)
                    PostScanAction.FIND_OLD -> findOldFiles(summary)
                    PostScanAction.REVIEW_UNCATEGORIZED -> findUncategorized(summary)
                }
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
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
                val root = summary.scopeRoot
                if (root !is FileRef.Direct) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }
                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
                val apkRecords = records.filter { classifyByExtension(it.extension) == FileCategory.APK }
                if (apkRecords.isEmpty()) {
                    _uiState.value = ScanUiState.Error("No APKs found under ${summary.scopeLabel}.")
                    return@launch
                }

                val apksFolder = FileRef.Direct("${root.absolutePath.trimEnd('/')}/APKs")
                val operations = buildList {
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
                val index = InMemoryFileIndex(container.database.fileRecordDao().getAllUnderScopeRoot(root.rawValue()))
                val validated = PlanValidator.validate(operations, index)

                _uiState.value = ScanUiState.PlanPreview(
                    goal = "Organize APKs under ${summary.scopeLabel}",
                    accepted = validated.accepted,
                    rejected = validated.rejected,
                    scopeRoot = root,
                )
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
    fun proposeSmartCleanup(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working("Planning cleanup", "Classifying files under ${summary.scopeLabel}")
            try {
                val root = summary.scopeRoot
                if (root !is FileRef.Direct) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }
                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
                val projectKeywords = settingsRepository.projectKeywords.first()
                val plan = withContext(Dispatchers.Default) {
                    RuleBasedPlanSource.proposePlan(PlanRequest(root, records, projectKeywords))
                }
                if (plan.operations.isEmpty()) {
                    _uiState.value = ScanUiState.Error("Nothing under ${summary.scopeLabel} could be classified with full confidence — nothing to propose. Review uncategorized to see what was skipped and why.")
                    return@launch
                }

                showPlanPreview(plan.goal, plan.operations, root)
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
                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(summary.scopeRoot.rawValue())
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
    private suspend fun showPlanPreview(goal: String, operations: List<PlannedOperation>, root: FileRef) {
        val index = InMemoryFileIndex(container.database.fileRecordDao().getAllUnderScopeRoot(root.rawValue()))
        val validated = PlanValidator.validate(operations, index)
        _uiState.value = ScanUiState.PlanPreview(
            goal = goal,
            accepted = validated.accepted,
            rejected = validated.rejected,
            scopeRoot = root,
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
                val root = summary.scopeRoot
                val mode = summary.mode
                // SAF's openRead is still TODO(), so hashing would throw a raw
                // NotImplementedError rather than fail honestly. Guard here,
                // with the same message every other mutation-needing action
                // uses, instead of three different behaviors for one limit.
                if (mode == StorageAccessMode.SAF) {
                    _uiState.value = ScanUiState.Error(SAF_UNSUPPORTED)
                    return@launch
                }
                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
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
                _uiState.value = ScanUiState.DuplicateReview(groups, root, summary.scopeLabel)
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
                            reason = "Duplicate of ${group.keeper.displayName} (matching SHA-256)",
                        )
                    }
                }
                if (operations.isEmpty()) {
                    _uiState.value = ScanUiState.Error("No duplicates to trash.")
                    return@launch
                }
                showPlanPreview("Trash duplicate files under ${review.scopeLabel}", operations, review.scopeRoot)
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
                val records = container.database.fileRecordDao().getLargestFiles(summary.scopeRoot.rawValue(), limit)
                _uiState.value = ScanUiState.FileListReview("$limit largest files under ${summary.scopeLabel}", records)
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
                val records = container.database.fileRecordDao().getFilesOlderThan(summary.scopeRoot.rawValue(), cutoff)
                _uiState.value = ScanUiState.FileListReview("Files older than $olderThanMonths months under ${summary.scopeLabel}", records)
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun approvePlan(preview: ScanUiState.PlanPreview) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Working(
                label = "Organizing files",
                detail = "Starting",
                processed = 0,
                total = preview.accepted.size,
            )
            try {
                val accessState = settingsRepository.storageAccessState.first()
                val mode = accessState.mode ?: run {
                    _uiState.value = ScanUiState.Error("No storage access granted yet.")
                    return@launch
                }
                val executor = container.planExecutor(mode)
                // Only the operations the preview showed as accepted are
                // sent for execution — rejected ones stay untouched, per
                // Decision 5, rather than being re-submitted for the
                // executor's own validation pass to reject again.
                val plan = AgentPlan(preview.goal, preview.accepted)
                val summary = withContext(Dispatchers.IO) {
                    executor.execute(plan, preview.scopeRoot.rawValue(), mode) { completed, total ->
                        _uiState.value = ScanUiState.Working(
                            label = "Organizing files",
                            detail = "Moving and trashing — safe to interrupt, every step is journaled",
                            processed = completed,
                            total = total,
                        )
                    }
                }
                _uiState.value = ScanUiState.ExecutionDone(summary)
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun undoTask(taskRunId: Long) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Undoing(taskRunId)
            try {
                val summary = withContext(Dispatchers.IO) {
                    container.undoExecutor.undo(taskRunId)
                }
                _uiState.value = ScanUiState.UndoDone(summary)
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun reset() {
        autoStarted = false
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
    }

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
            is ScanTarget.GrantedFolder ->
                error("GrantedFolder target is only valid in SAF mode")
        }
    }
}
