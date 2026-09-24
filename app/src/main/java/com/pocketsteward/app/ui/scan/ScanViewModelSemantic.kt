package com.pocketsteward.app.ui.scan

import com.pocketsteward.app.ui.scan.ScanViewModel.Companion.COHERENCE_EXCERPT_CHARS
import com.pocketsteward.app.ui.scan.ScanViewModel.Companion.COHERENCE_BATCH_SIZE
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

// On-device coherence audit and the semantic organization / scheduled cleanup proposals built from it.
// Split out of ScanViewModel.kt unchanged; these are extension functions on the
// same ViewModel, so every call site and all behaviour is identical.

/**
 * M10B's first model feature. Read-only by construction: content is
 * extracted through ContentInspector, AgentModel receives bounded text,
 * and the result is rendered as advice rather than PlannedOperation.
 */
internal fun ScanViewModel.runCoherenceAudit(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working(
            "Coherence audit",
            "Selecting a representative cross-section from the local index",
        )
        try {
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

                val existing = if (summary.mode == StorageAccessMode.DIRECT) {
                    withContext(Dispatchers.IO) {
                        repository.indexedDocument(record.stableRef)
                    }
                } else {
                    null
                }
                val canUseIndex =
                    summary.mode == StorageAccessMode.DIRECT &&
                        ContentIndexPolicy.canReuse(existing, record) &&
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
internal fun ScanViewModel.proposeSemanticOrganization(
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
            val directScope = review.scopes.all { it.root is FileRef.Direct }
            if (!directScope && destinationPolicy != DestinationPolicy.ROOT_LOCAL) {
                _uiState.value = ScanUiState.Error(
                    "Selected-folder mode can only build document groups inside the granted tree.",
                )
                return@launch
            }

            @Suppress("DEPRECATION")
            val documentsRoot = if (directScope) {
                FileRef.Direct(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOCUMENTS,
                    ).absolutePath,
                )
            } else {
                null
            }

            val explicitRoot = if (destinationPolicy == DestinationPolicy.EXPLICIT_FOLDER) {
                val path = explicitDestinationPath?.trim()?.trimEnd('/')
                if (path.isNullOrBlank()) {
                    _uiState.value = ScanUiState.Error("Choose an existing destination folder first.")
                    return@launch
                }
                val ref = FileRef.Direct(path)
                val gateway = container.gatewayFor(StorageAccessMode.DIRECT)
                val valid = withContext(Dispatchers.IO) {
                    gateway.exists(ref) &&
                        runCatching { gateway.stat(ref).isDirectory }.getOrDefault(false)
                }
                if (!valid) {
                    _uiState.value = ScanUiState.Error(
                        "That destination folder does not exist or is not a directory.",
                    )
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
                val mode = if (directScope) StorageAccessMode.DIRECT else StorageAccessMode.SAF
                val repository = container.contentIndexRepository(mode)
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
                    scopeRoots = review.scopes.map { it.root },
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
                DestinationPolicy.ROOT_LOCAL ->
                    if (directScope) "inside each current scan root" else "inside the selected tree"
                DestinationPolicy.RECOMMENDED_DOCUMENTS ->
                    requireNotNull(documentsRoot).absolutePath
                DestinationPolicy.EXPLICIT_FOLDER ->
                    requireNotNull(explicitRoot).absolutePath
            }

            val notes = buildList {
                add("Semantic findings are advisory. This proposal was rebuilt deterministically from the current scan.")
                add("Grouping evidence priority: learned corrections → project keywords → repeated filename/title signals → indexed content → model advice.")
                add("Approved destination: $destinationLabel.")
                if (!directScope) {
                    add("Selected-folder mode cannot escape the granted Android document tree.")
                }
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
                val protected = result.skipped.count {
                    it.reason.contains("protected", ignoreCase = true)
                }
                if (protected > 0) {
                    add("$protected file(s) stayed untouched inside protected folders.")
                }
                val unsafe = result.skipped.count {
                    it.reason.contains("unsafe", ignoreCase = true)
                }
                if (unsafe > 0) {
                    add("$unsafe unsafe or unusable suggested group name(s) were ignored.")
                }
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

internal fun ScanViewModel.proposeScheduledCleanup(
    summary: ScanUiState.Summary,
    suggestion: PendingCleanupSuggestion,
) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working(
            "Planning scheduled review",
            "Considering only files discovered by the scheduled scan",
        )
        try {
            val exactRefs = suggestion.newFileRefs.toHashSet()
            val projectKeywords = settingsRepository.projectKeywords.first()
            val generatedByScope = summary.scopes.map { scope ->
                val records = ScheduledReviewPolicy.selectNewFiles(
                    records = container.database.fileRecordDao()
                        .getFilesUnderScopeRoot(scope.root.rawValue()),
                    suggestion = suggestion,
                )

                val generated = withContext(Dispatchers.Default) {
                    RuleBasedPlanSource.proposePlan(
                        PlanRequest(
                            scopeRoot = scope.root,
                            records = records,
                            projectKeywords = projectKeywords,
                            includeSubfolders = false,
                        ),
                    )
                }
                scope to generated
            }

            val operations = generatedByScope.flatMap { it.second.plan.operations }
            if (operations.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    "The scheduled scan found ${suggestion.newFileCount} new file(s), " +
                        "but none of the still-present new files can be organized with full confidence.",
                )
                return@launch
            }

            val tracked = exactRefs.size
            val notes = buildList {
                add(
                    "Scheduled review is limited to the $tracked newly discovered file reference(s) saved with this suggestion; older files are not reconsidered.",
                )
                if (suggestion.newFileCount > tracked) {
                    add(
                        "${suggestion.newFileCount - tracked} additional new file(s) were counted but omitted from the bounded persisted ref list. Run a normal cleanup if you want to review the whole root.",
                    )
                }
                generatedByScope.forEach { (scope, generated) ->
                    generated.scopeReport.previewLines().forEach { line ->
                        add(if (summary.scopes.size == 1) line else "${scope.label}: $line")
                    }
                }
            }

            showPlanPreview(
                goal = "Scheduled cleanup review · ${suggestion.newFileCount} new file(s)",
                operations = operations,
                scopes = summary.scopes,
                scopeNotes = notes,
            )
            // Consume only after a real proposal exists. A failed scan
            // or an empty/failed proposal must remain reviewable later.
            settingsRepository.clearPendingCleanupSuggestion()
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}
