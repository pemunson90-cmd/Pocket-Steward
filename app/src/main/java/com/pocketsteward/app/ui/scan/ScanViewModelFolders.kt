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

// Folder browser, folder protection markers and the uncategorized review.
// Split out of ScanViewModel.kt unchanged; these are extension functions on the
// same ViewModel, so every call site and all behaviour is identical.

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
internal fun ScanViewModel.browseFolders(
    startAt: FileRef? = null,
    ancestors: List<FileRef> = emptyList(),
) {
    viewModelScope.launch {
        val startLabel = startAt?.displayScopeLabel() ?: "Storage root"
        _uiState.value = ScanUiState.Working("Reading folders", startLabel)
        try {
            val access = settingsRepository.storageAccessState.first()
            val mode = access.mode ?: run {
                _uiState.value = ScanUiState.Error("No storage access is active.")
                return@launch
            }
            val gateway = container.gatewayFor(mode)

            val storageRoot: FileRef = when (mode) {
                StorageAccessMode.DIRECT -> {
                    @Suppress("DEPRECATION")
                    FileRef.Direct(Environment.getExternalStorageDirectory().absolutePath)
                }
                StorageAccessMode.SAF -> {
                    val uri = access.safTreeUri ?: run {
                        _uiState.value = ScanUiState.Error(
                            "The selected-folder grant is no longer available. Choose the folder again.",
                        )
                        return@launch
                    }
                    gateway.rootOf(
                        StorageScope.Tree(
                            rootRef = FileRef.Saf(uri),
                            displayName = "Selected folder",
                        ),
                    )
                }
            }

            val current = startAt ?: storageRoot
            val effectiveAncestors = when {
                ancestors.isNotEmpty() -> ancestors
                mode == StorageAccessMode.DIRECT && current is FileRef.Direct && storageRoot is FileRef.Direct ->
                    current.directAncestorsWithin(storageRoot)
                current.rawValue().trimEnd('/') == storageRoot.rawValue().trimEnd('/') -> emptyList()
                else -> emptyList()
            }

            val children = withContext(Dispatchers.IO) {
                gateway.listChildren(current)
                    .filter { it.isDirectory }
                    .map { entry -> describeFolder(gateway, entry.ref, entry.displayName) }
            }

            _uiState.value = ScanUiState.FolderBrowser(
                root = storageRoot,
                current = current,
                currentDisplayName = runCatching { gateway.stat(current).displayName }
                    .getOrDefault(current.displayScopeLabel()),
                currentIsProtected = withContext(Dispatchers.IO) {
                    gateway.exists(current.child(DO_NOT_SORT_MARKER))
                },
                children = children,
                ancestors = effectiveAncestors,
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
internal suspend fun ScanViewModel.describeFolder(
    gateway: StorageGateway,
    ref: FileRef,
    displayName: String,
): BrowsableFolder {
    val contents = runCatching { gateway.listChildren(ref) }.getOrDefault(emptyList())
    val files = contents.filter { !it.isDirectory }
    val stats = files.mapNotNull { runCatching { gateway.stat(it.ref) }.getOrNull() }
    return BrowsableFolder(
        ref = ref,
        folder = PickerFolder(
            path = ref.rawValue(),
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
internal fun ScanViewModel.scanBrowsedFolder(folder: FileRef) {
    when (folder) {
        is FileRef.Direct -> startScan(ScanTarget.CustomFolder(folder.absolutePath))
        is FileRef.Saf -> startScan(
            ScanTarget.GrantedSubfolder(
                documentUri = folder.documentUri,
                label = folder.displayScopeLabel(),
            ),
        )
        is FileRef.Child -> _uiState.value = ScanUiState.Error(
            "That folder has not been created yet and cannot be scanned.",
        )
    }
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
internal fun ScanViewModel.proposeToggleProtection(state: ScanUiState.FolderBrowser, folder: BrowsableFolder) {
    proposeToggleProtection(state.root, folder.ref, folder.displayName, folder.isProtected)
}

/** The same toggle for the folder currently open, rather than one listed inside it. */
internal fun ScanViewModel.proposeToggleProtectionHere(state: ScanUiState.FolderBrowser) {
    proposeToggleProtection(state.root, state.current, state.currentDisplayName, state.currentIsProtected)
}

internal fun ScanViewModel.proposeToggleProtection(
    root: FileRef,
    target: FileRef,
    displayName: String,
    isProtected: Boolean,
) {
    viewModelScope.launch {
        try {
            val operation = if (isProtected) {
                PlannedOperation.Trash(
                    source = target.child(DO_NOT_SORT_MARKER),
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
internal fun ScanViewModel.reviewFolderProtection(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Reading folders", "Checking which are already protected")
        try {
            val folders = summary.scopes.flatMap { scope ->
                val root = scope.root
                val records = container.database.fileRecordDao().getAllUnderScopeRoot(root.rawValue())
                withContext(Dispatchers.Default) {
                    val protectedFolders = SortScope.protectedFolders(records.map { it.toSortCandidate() })
                    val fileCounts = records.filter { !it.isDirectory }.groupingBy { it.parentRef }.eachCount()
                    val markerByParent = records
                        .filter { !it.isDirectory && it.displayName == DO_NOT_SORT_MARKER }
                        .mapNotNull { marker ->
                            marker.parentRef?.let { parent -> parent to marker.stableRef }
                        }
                        .toMap()
                    records
                        .filter { it.isDirectory && it.stableRef != root.rawValue() }
                        .sortedBy { it.stableRef }
                        .map { folder ->
                            val display = when (root) {
                                is FileRef.Direct ->
                                    folder.stableRef
                                        .removePrefix(root.absolutePath.trimEnd('/'))
                                        .trimStart('/')
                                        .ifBlank { folder.displayName }
                                else -> folder.displayName
                            }
                            ProtectableFolder(
                                stableRef = folder.stableRef,
                                displayName = display,
                                fileCount = fileCounts[folder.stableRef] ?: 0,
                                isProtected = folder.stableRef in protectedFolders,
                                markerStableRef = markerByParent[folder.stableRef],
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
internal fun ScanViewModel.proposeToggleProtection(state: ScanUiState.ProtectFolders, folder: ProtectableFolder) {
    viewModelScope.launch {
        try {
            val folderRef = parseFileRef(folder.stableRef)
            val operation = if (folder.isProtected) {
                // The marker was indexed by the scan like any other
                // file, so the ordinary scan-index preview validates
                // this — unlike the browser, which reaches folders no
                // scan has walked.
                val marker = folder.markerStableRef
                    ?: error("The protection marker is no longer present in the scan index.")
                PlannedOperation.Trash(
                    source = parseFileRef(marker),
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
internal fun ScanViewModel.findUncategorized(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Reviewing", "Checking what the rules can't place")
        try {
            val records = filesForScopes(summary.scopes)
            val projectKeywords = settingsRepository.projectKeywords.first()
            val classified = withContext(Dispatchers.Default) {
                records.map { record ->
                    record to RuleEngine.classify(
                        record.displayName,
                        record.extension,
                        projectKeywords,
                    )
                }
            }
            val uncategorized = classified
                .filter { (_, classification) -> classification.isUncategorized() }
                .map { it.first }
            _uiState.value = ScanUiState.FileListReview(
                title = "Uncategorized under ${summary.scopeLabel}",
                records = uncategorized,
                explanationByRef = classified
                    .filter { (_, classification) -> classification.isUncategorized() }
                    .associate { (record, classification) ->
                        record.stableRef to classification.reason
                    },
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}
