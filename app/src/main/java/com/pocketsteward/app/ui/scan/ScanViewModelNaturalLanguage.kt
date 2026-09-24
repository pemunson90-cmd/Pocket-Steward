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

// Bounded natural-language requests and the transfer/rename plans they produce.
// Split out of ScanViewModel.kt unchanged; these are extension functions on the
// same ViewModel, so every call site and all behaviour is identical.

internal fun ScanViewModel.handleNaturalLanguage(
    summary: ScanUiState.Summary,
    request: String,
    allowModelFallback: Boolean = true,
) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Understanding request", "Offline deterministic parser")
        try {
            when (val parsed = DeterministicIntentParser.parse(request, lastBoundedIntent)) {
                is IntentParseResult.Unsupported -> {
                    if (!allowModelFallback) {
                        _uiState.value = ScanUiState.Error(parsed.reason)
                        return@launch
                    }

                    val privacy = settingsRepository.privacySettings.first()
                    if (!privacy.onDeviceAiEnabled ||
                        container.agentModel.availability() != AgentModelAvailability.AVAILABLE
                    ) {
                        _uiState.value = ScanUiState.Error(
                            parsed.reason +
                                " Enable on-device intelligence in Settings for a local language fallback.",
                        )
                        return@launch
                    }

                    _uiState.value = ScanUiState.Working(
                        "Understanding request",
                        "Trying the on-device language model, then re-validating with the deterministic parser",
                    )
                    val normalized = container.agentModel.normalizeIntentRequest(request)
                    if (normalized.isNullOrBlank()) {
                        _uiState.value = ScanUiState.Error(
                            parsed.reason + " The on-device model could not translate it safely either.",
                        )
                        return@launch
                    }

                    // The model gets no filesystem authority. Its output
                    // re-enters this function once with model fallback
                    // disabled, so only DeterministicIntentParser can
                    // produce the typed BoundedIntent used below.
                    handleNaturalLanguage(
                        summary = summary,
                        request = normalized,
                        allowModelFallback = false,
                    )
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

                        IntentAction.MOVE,
                        IntentAction.COPY,
                        -> {
                            val destinationText = intent.destinationFolder
                                ?: run {
                                    _uiState.value = ScanUiState.Error("Choose a destination folder.")
                                    return@launch
                                }

                            if (summary.mode == StorageAccessMode.SAF) {
                                val transfer = prepareSafTransfer(
                                    summary = summary,
                                    intent = intent,
                                    destinationText = destinationText,
                                )
                                if (transfer.operations.isEmpty()) {
                                    _uiState.value = ScanUiState.Error(
                                        "The request was understood, but no matching files were found.",
                                    )
                                    return@launch
                                }
                                showPlanPreview(
                                    goal = intent.rawRequest,
                                    operations = transfer.operations,
                                    scopes = summary.scopes,
                                    scopeNotes = listOf(
                                        "Selected-tree destination: ${transfer.destinationLabel}",
                                        if (intent.action == IntentAction.COPY) {
                                            "Copy keeps the original file in place. Undo quarantines only the created copy."
                                        } else {
                                            "Move removes the original only after the copied destination is verified."
                                        },
                                    ),
                                )
                                return@launch
                            }

                            val transfer = prepareExplicitTransfer(
                                summary = summary,
                                intent = intent,
                                destinationText = destinationText,
                            )
                            if (transfer.operations.isEmpty()) {
                                _uiState.value = ScanUiState.Error(
                                    "The request was understood, but no matching files were found.",
                                )
                                return@launch
                            }

                            showPlanPreview(
                                goal = intent.rawRequest,
                                operations = transfer.operations,
                                scopes = summary.scopes,
                                scopeNotes = listOf(
                                    "Explicit transfer destination: ${transfer.destinationDirectory.absolutePath}",
                                    if (intent.action == IntentAction.COPY) {
                                        "Copy keeps the original file in place. Undo moves the created copy to PocketSteward/Trash."
                                    } else {
                                        "Move removes the file from its current folder only after the approved operation succeeds."
                                    },
                                ),
                                authorizedDestinationRoots = listOf(transfer.authorizedRoot),
                            )
                        }

                        IntentAction.RENAME -> {
                            val batchTerm = intent.renameMatchTerm
                            val batchTemplate = intent.renameTemplate
                            if (batchTerm != null && batchTemplate != null) {
                                val matches = filesForScopes(summary.scopes)
                                    .filter { record ->
                                        !record.isDirectory &&
                                            record.displayName.contains(batchTerm, ignoreCase = true)
                                    }
                                    .sortedBy { it.stableRef }

                                if (matches.isEmpty()) {
                                    _uiState.value = ScanUiState.Error(
                                        "No files matching \"$batchTerm\" were found in the selected scope.",
                                    )
                                    return@launch
                                }
                                if (matches.size > 1 && "{n}" !in batchTemplate) {
                                    _uiState.value = ScanUiState.Error(
                                        "That template would rename multiple files to the same name. Add {n}, for example \"Vacation-{n}.{ext}\".",
                                    )
                                    return@launch
                                }

                                val width = matches.size.toString().length.coerceAtLeast(1)
                                val operations = matches.mapIndexed { index, record ->
                                    val newName = renderRenameTemplate(
                                        template = batchTemplate,
                                        record = record,
                                        ordinal = index + 1,
                                        ordinalWidth = width,
                                    )
                                    require(
                                        newName.isNotBlank() &&
                                            '/' !in newName &&
                                            '\\' !in newName &&
                                            newName != ".."
                                    ) {
                                        "The rename template produced an unsafe file name."
                                    }

                                    PlannedOperation.Rename(
                                        source = parseFileRef(record.stableRef),
                                        newName = newName,
                                        reason = "Deterministic batch rename matching \"$batchTerm\"",
                                    )
                                }

                                showPlanPreview(
                                    goal = intent.rawRequest,
                                    operations = operations,
                                    scopes = summary.scopes,
                                    scopeNotes = listOf(
                                        "Batch rename template: $batchTemplate",
                                        "Supported placeholders: {n}, {name}, {ext}. Ordering is deterministic by full source path.",
                                    ),
                                )
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
                            val projectKeywords = settingsRepository.projectKeywords.first()
                            val generatedByScope = summary.scopes.map { scope ->
                                val root = scope.root
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
internal data class PreparedTransfer(
    val operations: List<PlannedOperation>,
    val authorizedRoot: FileRef.Direct,
    val destinationDirectory: FileRef.Direct,
)

internal data class PreparedSafTransfer(
    val operations: List<PlannedOperation>,
    val destinationLabel: String,
)

internal suspend fun ScanViewModel.prepareSafTransfer(
    summary: ScanUiState.Summary,
    intent: BoundedIntent,
    destinationText: String,
): PreparedSafTransfer {
    require(summary.mode == StorageAccessMode.SAF) {
        "Selected-tree transfer helper requires SAF mode."
    }
    val root = summary.scopes.singleOrNull()?.root
        ?: error("Selected-folder mode must have exactly one granted tree.")

    val raw = destinationText.trim().trim('/')
    require(raw.isNotBlank() && !raw.startsWith("content:", ignoreCase = true)) {
        "Choose a folder inside the selected tree, for example \"Archive\" or \"Projects/NSTL\"."
    }
    val segments = raw.split('/')
        .map { it.trim() }
        .filter { it.isNotBlank() }
    require(segments.isNotEmpty() && segments.none {
        it == "." || it == ".." || '\\' in it || it.any(Char::isISOControl)
    }) {
        "Destination contains an unsafe folder segment."
    }

    val createOperations = mutableListOf<PlannedOperation>()
    var parent: FileRef = root
    for (segment in segments) {
        createOperations += PlannedOperation.CreateDirectory(
            parent = parent,
            name = segment,
            reason = "Destination inside the selected Android document tree",
        )
        parent = parent.child(segment)
    }
    val destinationDirectory = parent

    val rootRaw = root.rawValue().trimEnd('/')
    val records = filesForScopes(summary.scopes)
        .asSequence()
        .filter { !it.isDirectory }
        .filter { record ->
            intent.includeSubfolders ||
                record.parentRef?.trimEnd('/') == rootRaw
        }
        .filter { record ->
            intent.categories.isEmpty() ||
                classifyByExtension(record.extension) in intent.categories
        }
        .filter { record ->
            intent.findTerm.isNullOrBlank() ||
                record.displayName.contains(intent.findTerm, ignoreCase = true)
        }
        .toList()

    val matching = applyIntentCriteria(records, intent)
    val transferOperations = matching.map { record ->
        val source = parseFileRef(record.stableRef)
        val destination = destinationDirectory.child(record.displayName)
        when (intent.action) {
            IntentAction.MOVE -> PlannedOperation.Move(
                source = source,
                destination = destination,
                reason = "Explicit natural-language move inside selected tree",
            )
            IntentAction.COPY -> PlannedOperation.Copy(
                source = source,
                destination = destination,
                reason = "Explicit natural-language copy inside selected tree",
            )
            else -> error("SAF transfer helper called for ${intent.action}.")
        }
    }

    return PreparedSafTransfer(
        operations = createOperations + transferOperations,
        destinationLabel = segments.joinToString("/"),
    )
}

internal suspend fun ScanViewModel.prepareExplicitTransfer(
    summary: ScanUiState.Summary,
    intent: BoundedIntent,
    destinationText: String,
): PreparedTransfer {
    val sharedRoot = java.io.File(Environment.getExternalStorageDirectory().absolutePath).canonicalFile
    val requested = java.io.File(destinationText)
    val destinationFile = if (requested.isAbsolute) {
        requested.canonicalFile
    } else {
        java.io.File(sharedRoot, destinationText).canonicalFile
    }

    require(
        destinationFile.path == sharedRoot.path ||
            destinationFile.path.startsWith(sharedRoot.path.trimEnd(java.io.File.separatorChar) + java.io.File.separator),
    ) {
        "Destination must stay inside shared storage."
    }

    val gateway = container.gatewayFor(StorageAccessMode.DIRECT)
    if (withContext(Dispatchers.IO) { gateway.exists(FileRef.Direct(destinationFile.path)) }) {
        val metadata = withContext(Dispatchers.IO) {
            gateway.stat(FileRef.Direct(destinationFile.path))
        }
        require(metadata.isDirectory) { "Destination exists but is not a folder." }
    }

    var ancestor = destinationFile
    while (
        ancestor.path != sharedRoot.path &&
        !withContext(Dispatchers.IO) { gateway.exists(FileRef.Direct(ancestor.path)) }
    ) {
        ancestor = ancestor.parentFile
            ?: error("Could not resolve an existing destination ancestor.")
    }
    require(
        withContext(Dispatchers.IO) {
            val ref = FileRef.Direct(ancestor.path)
            gateway.exists(ref) && gateway.stat(ref).isDirectory
        },
    ) {
        "No existing destination ancestor is available."
    }

    val destinationRef = FileRef.Direct(destinationFile.path)
    val ancestorRef = FileRef.Direct(ancestor.path)
    val createOperations = mutableListOf<PlannedOperation>()
    var parent = ancestorRef

    if (ancestor.path != destinationFile.path) {
        val relative = destinationFile.relativeTo(ancestor).invariantSeparatorsPath
        relative.split('/').filter { it.isNotBlank() }.forEach { segment ->
            require(segment != ".." && '/' !in segment && '\\' !in segment) {
                "Unsafe destination folder segment."
            }
            createOperations += PlannedOperation.CreateDirectory(
                parent = parent,
                name = segment,
                reason = "Explicit destination requested by the user",
            )
            parent = FileRef.Direct("${parent.absolutePath.trimEnd('/')}/$segment")
        }
    }

    val directParents = summary.scopes
        .mapNotNull { (it.root as? FileRef.Direct)?.absolutePath?.trimEnd('/') }
        .toSet()
    val candidates = filesForScopes(summary.scopes)
        .asSequence()
        .filter { !it.isDirectory }
        .filter { record ->
            intent.includeSubfolders ||
                record.parentRef?.trimEnd('/') in directParents
        }
        .filter { record ->
            intent.categories.isEmpty() ||
                classifyByExtension(record.extension) in intent.categories
        }
        .filter { record ->
            intent.findTerm.isNullOrBlank() ||
                record.displayName.contains(intent.findTerm, ignoreCase = true)
        }
        .toList()

    val matching = applyIntentCriteria(candidates, intent)
    val transferOperations = matching.map { record ->
        val source = parseFileRef(record.stableRef)
        val destination = FileRef.Direct(
            "${destinationRef.absolutePath.trimEnd('/')}/${record.displayName}",
        )
        when (intent.action) {
            IntentAction.MOVE -> PlannedOperation.Move(
                source = source,
                destination = destination,
                reason = "Explicit natural-language move request",
            )
            IntentAction.COPY -> PlannedOperation.Copy(
                source = source,
                destination = destination,
                reason = "Explicit natural-language copy request",
            )
            else -> error("Transfer helper called for ${intent.action}.")
        }
    }

    return PreparedTransfer(
        operations = createOperations + transferOperations,
        authorizedRoot = ancestorRef,
        destinationDirectory = destinationRef,
    )
}

internal fun ScanViewModel.renderRenameTemplate(
    template: String,
    record: FileRecord,
    ordinal: Int,
    ordinalWidth: Int,
): String {
    val baseName = record.displayName.substringBeforeLast('.', record.displayName)
    val extension = record.extension
    var rendered = template
        .replace("{n}", ordinal.toString().padStart(ordinalWidth, '0'))
        .replace("{name}", baseName)
        .replace("{ext}", extension)

    if ("{ext}" !in template &&
        '.' !in rendered &&
        extension.isNotBlank()
    ) {
        rendered += ".$extension"
    }
    return rendered
}

internal fun ScanViewModel.applyIntentCriteria(
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

internal fun BoundedIntent.toContentSearchSort(): ContentSearchSort = when (order) {
    IntentOrder.DEFAULT -> ContentSearchSort.RELEVANCE
    IntentOrder.LARGEST_FIRST -> ContentSearchSort.LARGEST
    IntentOrder.SMALLEST_FIRST -> ContentSearchSort.SMALLEST
    IntentOrder.NEWEST_FIRST -> ContentSearchSort.MODIFIED_NEWEST
    IntentOrder.OLDEST_FIRST -> ContentSearchSort.MODIFIED_OLDEST
}
