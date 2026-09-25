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

// Editing a proposed plan before approval: single operations, destination groups and selection.
// Split out of ScanViewModel.kt unchanged; these are extension functions on the
// same ViewModel, so every call site and all behaviour is identical.

internal fun ScanViewModel.editPlanOperation(
    operationIndex: Int,
    newDestinationPath: String? = null,
    newName: String? = null,
    keepOriginal: Boolean? = null,
) {
    val current = _preview.value ?: return
    if (operationIndex !in current.accepted.indices) return

    viewModelScope.launch {
        try {
            val original = current.accepted[operationIndex]
            val edited = when (original) {
                is PlannedOperation.Move -> {
                    when {
                        keepOriginal == true -> PlannedOperation.Copy(
                            source = original.source,
                            destination = original.destination,
                            reason = original.reason,
                        )
                        newDestinationPath != null -> {
                            if (original.destination !is FileRef.Direct) {
                                _error.value = "Selected-tree destinations are edited by group, not by raw path."
                                return@launch
                            }
                            val path = newDestinationPath.trim().trimEnd('/')
                            if (path.isBlank()) {
                                _error.value = "Move destination cannot be blank."
                                return@launch
                            }
                            original.copy(destination = FileRef.Direct(path))
                        }
                        else -> original
                    }
                }

                is PlannedOperation.Copy -> {
                    when {
                        keepOriginal == false -> PlannedOperation.Move(
                            source = original.source,
                            destination = original.destination,
                            reason = original.reason,
                        )
                        newDestinationPath != null -> {
                            if (original.destination !is FileRef.Direct) {
                                _error.value = "Selected-tree destinations are edited by group, not by raw path."
                                return@launch
                            }
                            val path = newDestinationPath.trim().trimEnd('/')
                            if (path.isBlank()) {
                                _error.value = "Copy destination cannot be blank."
                                return@launch
                            }
                            original.copy(destination = FileRef.Direct(path))
                        }
                        else -> original
                    }
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

            if (edited == original) return@launch

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
                        is PlannedOperation.Copy -> listOfNotNull(
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

            val editMode = if (current.scopes.all { it.root is FileRef.Direct }) {
                StorageAccessMode.DIRECT
            } else {
                StorageAccessMode.SAF
            }
            val index = buildAuthorizedPlanIndex(
                scopes = current.scopes,
                destinationRoots = if (editMode == StorageAccessMode.DIRECT) extraRoots else emptyList(),
                mode = editMode,
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

internal fun ScanViewModel.editSafPlanDestinationGroup(
    groupDirectory: FileRef.Child,
    newGroupName: String,
    rememberForSimilarFiles: Boolean = false,
) {
    val current = _preview.value ?: return
    viewModelScope.launch {
        try {
            require(current.scopes.any { it.root is FileRef.Saf }) {
                "Selected-tree destination editing requires a selected-tree scan."
            }
            val targetGroup = SemanticPlanAdapter.sanitizeGroup(newGroupName)
                ?: run {
                    _error.value = "Group name is blank or contains an unsafe path separator."
                    return@launch
                }
            val replacement = FileRef.Child(groupDirectory.parent, targetGroup)
            if (replacement == groupDirectory) return@launch

            val records = allRecordsForScopes(current.scopes)
            val displayNameByRef = records.associate { it.stableRef to it.displayName }
            val affectedSourceNames = current.accepted.mapNotNull { operation ->
                val source = when (operation) {
                    is PlannedOperation.Move -> operation.source
                    is PlannedOperation.Copy -> operation.source
                    else -> return@mapNotNull null
                }
                val destination = when (operation) {
                    is PlannedOperation.Move -> operation.destination
                    is PlannedOperation.Copy -> operation.destination
                    else -> return@mapNotNull null
                }
                if (destination.knownParentOrNull()?.rawValue() == groupDirectory.rawValue()) {
                    displayNameByRef[source.rawValue()]
                } else {
                    null
                }
            }

            val transformed = current.accepted.map { operation ->
                when (operation) {
                    is PlannedOperation.CreateDirectory -> {
                        val created = operation.parent.child(operation.name)
                        when {
                            created.rawValue() == groupDirectory.rawValue() ->
                                operation.copy(
                                    parent = replacement.parent,
                                    name = replacement.name,
                                )
                            else -> {
                                val parent = rebasePlannedDestination(
                                    operation.parent,
                                    groupDirectory,
                                    replacement,
                                )
                                if (parent == operation.parent) operation else operation.copy(parent = parent)
                            }
                        }
                    }

                    is PlannedOperation.Move -> {
                        val destination = rebasePlannedDestination(
                            operation.destination,
                            groupDirectory,
                            replacement,
                        )
                        if (destination == operation.destination) operation else operation.copy(destination = destination)
                    }

                    is PlannedOperation.Copy -> {
                        val destination = rebasePlannedDestination(
                            operation.destination,
                            groupDirectory,
                            replacement,
                        )
                        if (destination == operation.destination) operation else operation.copy(destination = destination)
                    }

                    is PlannedOperation.WriteTextFile -> {
                        val parent = rebasePlannedDestination(
                            operation.parent,
                            groupDirectory,
                            replacement,
                        )
                        if (parent == operation.parent) operation else operation.copy(parent = parent)
                    }

                    is PlannedOperation.Rename,
                    is PlannedOperation.Trash,
                    -> operation
                }
            }

            val validated = PlanValidator.validate(
                transformed,
                InMemoryFileIndex(records),
            )
            if (validated.rejected.isNotEmpty() || validated.accepted.size != transformed.size) {
                _error.value = validated.rejected.firstOrNull()?.reason
                    ?: "The edited selected-tree destination no longer validates."
                return@launch
            }

            _preview.value = current.copy(
                accepted = validated.accepted,
                acceptedScopeLabels = validated.accepted.map { operation ->
                    scopeForOperation(operation, current.scopes)?.label ?: current.scopeLabel
                },
                selectedIndices = current.selectedIndices
                    .filterTo(linkedSetOf()) { it in validated.accepted.indices },
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

internal fun ScanViewModel.rebasePlannedDestination(
    ref: FileRef,
    oldBase: FileRef.Child,
    newBase: FileRef.Child,
): FileRef {
    if (ref.rawValue() == oldBase.rawValue()) return newBase
    if (ref !is FileRef.Child) return ref
    val parent = rebasePlannedDestination(ref.parent, oldBase, newBase)
    return if (parent == ref.parent) ref else FileRef.Child(parent, ref.name)
}

internal fun ScanViewModel.editPlanDestinationGroup(
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

            val affectedSourceNames = current.accepted.mapNotNull { operation ->
                val source = when (operation) {
                    is PlannedOperation.Move -> operation.source
                    is PlannedOperation.Copy -> operation.source
                    else -> return@mapNotNull null
                }
                val destination = when (operation) {
                    is PlannedOperation.Move -> operation.destination
                    is PlannedOperation.Copy -> operation.destination
                    else -> return@mapNotNull null
                } as? FileRef.Direct ?: return@mapNotNull null

                if (destination.absolutePath
                        .substringBeforeLast('/', missingDelimiterValue = "") == oldDirectory
                ) {
                    (source as? FileRef.Direct)?.absolutePath?.substringAfterLast('/')
                } else {
                    null
                }
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

                    is PlannedOperation.Copy -> {
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
            if (validated.rejected.isNotEmpty() ||
                validated.accepted.size != transformed.size
            ) {
                _error.value = validated.rejected.firstOrNull()?.reason
                    ?: "The edited destination no longer validates against current storage."
                return@launch
            }

            val updatedFilingPresentation = current.filingPresentation?.let { filing ->
                val newDirectory = "$targetRoot/$targetGroup"
                filing.copy(
                    groups = filing.groups.map { group ->
                        if (group.destinationPath.trimEnd('/') != oldDirectory) {
                            group
                        } else if (group.release != null) {
                            group.copy(
                                projectName = targetRoot.substringAfterLast('/').ifBlank { group.projectName },
                                projectHomePath = targetRoot,
                                destinationPath = newDirectory,
                                existingProjectHome = true,
                                release = targetGroup,
                                items = group.items.map { it.copy(destinationPath = newDirectory) },
                            )
                        } else {
                            group.copy(
                                projectName = targetGroup,
                                projectHomePath = newDirectory,
                                destinationPath = newDirectory,
                                existingProjectHome = true,
                                items = group.items.map { it.copy(destinationPath = newDirectory) },
                            )
                        }
                    },
                )
            }

            _preview.value = current.copy(
                accepted = validated.accepted,
                acceptedScopeLabels = validated.accepted.map { operation ->
                    scopeForOperation(operation, current.scopes)?.label ?: "Approved destination"
                },
                // An edit must never change what the user selected.
                // Operations stay in the same order because an invalid
                // transformed plan is rejected as a whole above.
                selectedIndices = current.selectedIndices
                    .filterTo(linkedSetOf()) { it in validated.accepted.indices },
                authorizedDestinationRoots = extraRoots,
                filingPresentation = updatedFilingPresentation,
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

internal fun ScanViewModel.learnableFilenameTerm(names: List<String>): String? {
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

internal fun ScanViewModel.setPlanOperationSelected(index: Int, selected: Boolean) {
    val current = _preview.value ?: return
    var updated = PlanSelection.setSelected(
        operations = current.accepted,
        current = current.selectedIndices,
        index = index,
        selected = selected,
    )

    // A nested filing move may depend on one or more CreateDirectory actions.
    // Selecting the file should never leave its required parent unchecked.
    if (selected) {
        val destination = when (val operation = current.accepted.getOrNull(index)) {
            is PlannedOperation.Move -> operation.destination.rawValue()
            is PlannedOperation.Copy -> operation.destination.rawValue()
            else -> null
        }
        if (destination != null) {
            current.accepted.forEachIndexed { createIndex, operation ->
                if (operation is PlannedOperation.CreateDirectory) {
                    val directory = operation.parent.child(operation.name).rawValue().trimEnd('/')
                    if (destination == directory || destination.startsWith("$directory/")) {
                        updated = PlanSelection.setSelected(
                            operations = current.accepted,
                            current = updated,
                            index = createIndex,
                            selected = true,
                        )
                    }
                }
            }
        }
    }

    if (current.filingPresentation != null) {
        val selectedDestinations = current.accepted.mapIndexedNotNull { operationIndex, operation ->
            if (operationIndex !in updated) return@mapIndexedNotNull null
            when (operation) {
                is PlannedOperation.Move -> operation.destination.rawValue()
                is PlannedOperation.Copy -> operation.destination.rawValue()
                else -> null
            }
        }
        current.accepted.forEachIndexed { createIndex, operation ->
            if (operation is PlannedOperation.CreateDirectory) {
                val directory = operation.parent.child(operation.name).rawValue().trimEnd('/')
                val needed = selectedDestinations.any { destination ->
                    destination == directory || destination.startsWith("$directory/")
                }
                updated = if (needed) {
                    updated + createIndex
                } else {
                    updated - createIndex
                }
            }
        }
    }

    _preview.value = current.copy(selectedIndices = updated)
}

internal fun ScanViewModel.selectAllPlanOperations() {
    val current = _preview.value ?: return
    _preview.value = current.copy(
        selectedIndices = PlanSelection.allSelected(current.accepted),
    )
}

internal fun ScanViewModel.selectRecommendedPlanOperations() {
    val current = _preview.value ?: return
    val filing = current.filingPresentation
    if (filing == null) {
        selectSafePlanOperations()
        return
    }
    val strongRefs = filing.groups
        .flatMap { it.items }
        .filter { it.confidence == com.pocketsteward.app.filing.FilingConfidence.STRONG }
        .mapTo(linkedSetOf()) { it.sourceRef }
    _preview.value = current.copy(
        selectedIndices = defaultSelectionForSources(current.accepted, strongRefs),
    )
}

internal fun ScanViewModel.selectSafePlanOperations() {
    val current = _preview.value ?: return
    _preview.value = current.copy(
        selectedIndices = PlanSelection.safeSelected(current.accepted),
    )
}

internal fun ScanViewModel.clearPlanSelection() {
    val current = _preview.value ?: return
    _preview.value = current.copy(
        selectedIndices = PlanSelection.noneSelected(),
    )
}