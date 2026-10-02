package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import com.pocketsteward.app.ui.components.FileKind
import com.pocketsteward.app.ui.components.FileVisual
import com.pocketsteward.app.plan.PlanTree
import com.pocketsteward.app.filing.FilingConfidence
import com.pocketsteward.app.filing.FilingReviewPresentation
import com.pocketsteward.app.filing.FilingInventory
import com.pocketsteward.app.filing.FilingInventoryPolicy
import com.pocketsteward.app.filing.FilingOutcome
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.knownParentOrNull
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.plan.PlannedOperation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.pocketsteward.app.plan.MutationSafetyClass
import com.pocketsteward.app.plan.safetyClass
import com.pocketsteward.app.ui.theme.Spacing

/**
 * The gate. Nothing reaches [com.pocketsteward.app.executor.PlanExecutor]
 * without passing through here — splitting the flow into routes in M7 did not
 * create a second path, and must not.
 */
@Composable
fun PlanPreviewScreen(viewModel: ScanViewModel, onBack: () -> Unit) {
    val state by viewModel.preview.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val draftSaveStatus by viewModel.draftSaveStatus.collectAsState()
    val evidenceProgress by viewModel.evidenceAnalysisProgress.collectAsState()
    val evidenceError by viewModel.evidenceAnalysisError.collectAsState()
    val evidenceRunning = evidenceProgress?.status in setOf(
        com.pocketsteward.app.evidence.analysis.EvidenceAnalysisStatus.QUEUED,
        com.pocketsteward.app.evidence.analysis.EvidenceAnalysisStatus.RUNNING,
    )

    val preview = state
    var confirmBulkRed by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(PlanView.LIST) }
    var unresolvedQuery by rememberSaveable { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current

    ScanFlowScaffold(
        title = "Review changes",
        onBack = onBack,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
        onCancelWorking = if (viewModel.hasActiveFilingWork) viewModel::cancelFilingWork else null,
    ) { contentModifier ->
        if (preview == null) {
            EmptyState("Nothing proposed.", contentModifier)
            return@ScanFlowScaffold
        }

        val sourceOperationIndices = remember(preview.accepted) {
            buildMap<String, Int> {
                preview.accepted.forEachIndexed { index, operation ->
                    val source = when (operation) {
                        is PlannedOperation.Move -> operation.source
                        is PlannedOperation.Copy -> operation.source
                        is PlannedOperation.Rename -> operation.source
                        is PlannedOperation.Trash -> operation.source
                        else -> null
                    }
                    source?.rawValue()?.let { putIfAbsent(it, index) }
                }
            }
        }
        val filingInventory = remember(preview.filingPresentation, preview.accepted, preview.selectedIndices, preview.rejected) {
            preview.filingPresentation?.let { FilingInventoryPolicy.build(it, preview.accepted, preview.selectedIndices, preview.rejected) }
        }
        val inventoryBySource = remember(filingInventory) { filingInventory?.entries?.associateBy { it.item.sourceRef }.orEmpty() }

        Column(modifier = contentModifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.hairline)) {
                Text(
                    text = preview.goal,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (draftSaveStatus.isNotBlank()) Text(draftSaveStatus, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = buildString {
                        val selectedFiles = preview.selectedIndices.count { index ->
                            when (preview.accepted.getOrNull(index)) {
                                is PlannedOperation.Move, is PlannedOperation.Copy, is PlannedOperation.Rename, is PlannedOperation.Trash -> true
                                else -> false
                            }
                        }
                        if (preview.filingPresentation != null) {
                            append("$selectedFiles selected · ${preview.filingPresentation.proposedCount} project proposals")
                            if (preview.filingPresentation.checkpointCount > 0) {
                                append(" · ${preview.filingPresentation.checkpointCount} to Uncertain")
                            }
                            if (preview.filingPresentation.skippedInboxFolders > 0) {
                                append(" · ${preview.filingPresentation.skippedInboxFolders} folders left")
                            }
                        } else {
                            append("${preview.selectedIndices.size} selected · ${preview.accepted.size} available")
                        }
                        if (preview.rejected.isNotEmpty()) append(" · ${preview.rejected.size} not included")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
            }

            val redCount = preview.accepted.count {
                it.safetyClass() == MutationSafetyClass.RED
            }
            if (redCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        "$redCount quarantine/trash action(s) are intentionally unchecked by default. " +
                            "Select them deliberately, or use Select all if you have reviewed the whole set.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Spacing.base),
                    )
                }
            }

            // Now/After draw the selected actions as folder trees: the
            // shape of the result, which is what is actually being approved.
            // Display only; the executor still runs the selected list.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight)) {
                PlanView.entries.forEachIndexed { i, option ->
                    SegmentedButton(
                        modifier = Modifier.weight(1f),
                        selected = view == option,
                        onClick = { view = option },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = PlanView.entries.size),
                    ) {
                        Text(option.label, maxLines = 1)
                    }
                }
            }

            if (view == PlanView.LIST) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = Spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(Spacing.hairline),
            ) {
                OutlinedButton(
                    onClick = viewModel::selectRecommendedPlanOperations,
                    enabled = busy == null,
                    contentPadding = PaddingValues(horizontal = Spacing.tight),
                ) {
                    Text(if (preview.filingPresentation != null) "Strong only" else "Safe only", maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        if (redCount > 0) {
                            confirmBulkRed = true
                        } else {
                            viewModel.selectAllPlanOperations()
                        }
                    },
                    enabled = busy == null,
                    contentPadding = PaddingValues(horizontal = Spacing.tight),
                ) {
                    Text("Select all", maxLines = 1)
                }
                OutlinedButton(
                    onClick = viewModel::clearPlanSelection,
                    enabled = busy == null,
                    contentPadding = PaddingValues(horizontal = Spacing.tight),
                ) {
                    Text("Clear", maxLines = 1)
                }
            }

            if (view != PlanView.LIST) {
                val selectedOps = remember(preview.accepted, preview.selectedIndices) {
                    preview.selectedIndices.sorted().mapNotNull { preview.accepted.getOrNull(it) }
                }
                val trees = remember(selectedOps) { PlanTree.build(selectedOps) }
                PlanTreeView(
                    tree = if (view == PlanView.NOW) trees.before else trees.after,
                    after = view == PlanView.AFTER,
                    emptyMessage = if (selectedOps.isEmpty()) {
                        "Nothing selected yet. Pick actions in List to see their result here."
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
                contentPadding = PaddingValues(bottom = Spacing.tight),
            ) {
                val destinationGroups = preview.accepted
                    .mapNotNull(::destinationEditGroup)
                    .distinctBy { it.directory }
                val selectedTreeGroups = preview.accepted
                    .mapNotNull(::safDestinationEditGroup)
                    .distinctBy { it.directory.rawValue() }

                preview.filingPresentation?.let { filing ->
                    item {
                        FilingOverviewCard(filing, requireNotNull(filingInventory), busy == null,
                            onDeferRemaining = { viewModel.deferFilingFiles(filingInventory.pendingRefs) },
                            onKeepRemaining = { viewModel.keepFilingFiles(filingInventory.pendingRefs) })
                        Text("Analyze every reviewed image and document in the background, using your enabled local privacy settings. No files move during analysis.")
                        evidenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        evidenceProgress?.let { progress ->
                            Text(progress.status.name.lowercase().replace('_', ' '))
                            Text(progress.summary)
                            Text(progress.detail)
                            if (evidenceRunning) {
                                androidx.compose.material3.LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.processed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { viewModel.pauseFilingEvidenceAnalysis() }) { Text("Pause analysis") }
                            } else if (progress.status != com.pocketsteward.app.evidence.analysis.EvidenceAnalysisStatus.COMPLETED) {
                                OutlinedButton(onClick = { viewModel.resumeFilingEvidenceAnalysis() }) { Text("Resume saved analysis") }
                            }
                            if (!evidenceRunning && progress.processed > 0) {
                                OutlinedButton(onClick = { viewModel.continueFilingImageEvidence() }, enabled = busy == null) { Text("Apply saved evidence to review") }
                            }
                        }
                        OutlinedButton(onClick = { viewModel.analyzeAllFilingEvidence() }, enabled = !evidenceRunning && busy == null) { Text("Analyze all review evidence") }
                        evidenceProgress?.takeIf { !evidenceRunning && (it.unavailable > 0 || it.partial > 0) }?.let {
                            OutlinedButton(onClick = { viewModel.analyzeAllFilingEvidence(retryUnavailable = true) }, enabled = busy == null) { Text("Retry unavailable evidence across review") }
                        }
                        filing.imageCoverage?.takeIf { it.enabled }?.let { coverage ->
                            Text(coverage.summary)
                            if (coverage.hasDeferred && !evidenceRunning) {
                                OutlinedButton(onClick = { viewModel.continueFilingImageEvidence() }) {
                                    Text("Continue image evidence")
                                }
                            }
                            if (!evidenceRunning && (coverage.unavailable > 0 || coverage.unavailableOcr > 0)) {
                                OutlinedButton(onClick = { viewModel.continueFilingImageEvidence(retryUnavailable = true) }) {
                                    Text("Retry unavailable image evidence")
                                }
                            }
                        }
                        if (preview.scopeNotes.any { it.contains("Content inspection is off") }) {
                            Text("Read documents locally to improve project matching. Your assignments and selections are retained.")
                            OutlinedButton(onClick = { viewModel.enableContentAndReplanFiling() }) {
                                Text("Enable local content and rebuild")
                            }
                        }
                    }
                    if (filing.groups.isNotEmpty()) {
                        item { SectionHeader("Proposed folders") }
                    }
                    filing.groups.groupBy { it.projectHomePath }.forEach { (_, projectGroups) ->
                        item(key = "project:${projectGroups.first().projectHomePath}") {
                            Text(
                                "${projectGroups.first().projectName} · ${projectGroups.sumOf { it.items.size }} files",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = Spacing.tight),
                            )
                        }
                        items(projectGroups, key = { it.destinationPath }) { group ->
                            FilingDestinationCard(
                                group = group,
                                selectionEnabled = busy == null,
                                preview = preview,
                                sourceOperationIndices = sourceOperationIndices,
                                onSetSelected = viewModel::setPlanOperationSelected,
                                onConfirm = viewModel::confirmFilingFiles,
                                onDefer = viewModel::deferFilingFiles,
                                onKeep = viewModel::keepFilingFiles,
                                inventoryBySource = inventoryBySource,
                                onAssign = { refs, title, role, homePath, release -> viewModel.assignFilingFiles(refs, title, role, homePath, release) },
                                knownProjects = viewModel.filingSession?.homes.orEmpty(),
                                onApplyDestination = { root, name ->
                                    viewModel.editPlanDestinationGroup(
                                        groupDirectory = group.destinationPath,
                                        newDestinationRootPath = root,
                                        newGroupName = name,
                                        rememberForSimilarFiles = false,
                                    )
                                },
                            )
                        }
                    }
                    if (filing.checkpointGroups.isNotEmpty()) {
                        item { SectionHeader("Uncertain checkpoint") }
                        items(filing.checkpointGroups, key = { "checkpoint:${it.destinationPath}" }) { group ->
                            FilingDestinationCard(
                                group = group,
                                selectionEnabled = busy == null,
                                preview = preview,
                                sourceOperationIndices = sourceOperationIndices,
                                onSetSelected = viewModel::setPlanOperationSelected,
                                onConfirm = viewModel::confirmFilingFiles,
                                onDefer = viewModel::deferFilingFiles,
                                onKeep = viewModel::keepFilingFiles,
                                inventoryBySource = inventoryBySource,
                                onAssign = { refs, title, role, homePath, release -> viewModel.assignFilingFiles(refs, title, role, homePath, release) },
                                knownProjects = viewModel.filingSession?.homes.orEmpty(),
                                onApplyDestination = { _, _ -> },
                            )
                        }
                    }
                    if (filing.unresolved.isNotEmpty()) {
                        item {
                            OutlinedTextField(
                                value = unresolvedQuery, onValueChange = { unresolvedQuery = it },
                                label = { Text("Find unresolved files by name, evidence or content") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                        }
                        val matches = filing.unresolved.filter { item ->
                            unresolvedQuery.isBlank() || item.displayName.contains(unresolvedQuery, true) ||
                                item.contentExcerpt.orEmpty().contains(unresolvedQuery, true) || item.evidence.any { it.contains(unresolvedQuery, true) }
                        }
                        item {
                            FilingAssignmentEditor(
                                count = matches.size,
                                examples = matches.take(3).joinToString(" · ") { it.displayName },
                                knownProjects = viewModel.filingSession?.homes.orEmpty(),
                                onApply = { title, role, homePath, release -> viewModel.assignFilingFiles(matches.mapTo(hashSetOf()) { it.sourceRef }, title, role, homePath, release) },
                            )
                        }
                    }
                    val checkpointSourceRefs = filing.checkpointGroups.asSequence().flatMap { it.items.asSequence() }.map { it.sourceRef }.toHashSet()
                    val withoutCheckpoint = filing.unresolved.filter { item ->
                        item.sourceRef !in checkpointSourceRefs && (unresolvedQuery.isBlank() ||
                            item.displayName.contains(unresolvedQuery, true) || item.contentExcerpt.orEmpty().contains(unresolvedQuery, true) ||
                            item.evidence.any { it.contains(unresolvedQuery, true) })
                    }
                    if (withoutCheckpoint.isNotEmpty()) {
                        item { SectionHeader(if (filing.reviewingUncertain) "Still uncertain · stays here" else "Cannot move to Uncertain") }
                        items(withoutCheckpoint, key = { it.sourceRef }) { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(Spacing.base),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FileVisual(name = item.displayName, isDirectory = item.isDirectory, location = item.sourceRef)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                                        inventoryBySource[item.sourceRef]?.let { Text(it.reason, style = MaterialTheme.typography.labelSmall) }
                                        item.contentExcerpt?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                                        Text(
                                            buildString {
                                                append(formatBytes(item.sizeBytes)).append(" · ")
                                                append(item.evidence.firstOrNull() ?: "Not enough project evidence to move safely.")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (preview.filingPresentation == null && (destinationGroups.isNotEmpty() || selectedTreeGroups.isNotEmpty())) {
                    item { SectionHeader("Destinations") }
                    items(destinationGroups, key = { it.directory }) { group ->
                        DestinationEditCard(
                            group = group,
                            onApply = { root, name, rememberRule ->
                                viewModel.editPlanDestinationGroup(
                                    groupDirectory = group.directory,
                                    newDestinationRootPath = root,
                                    newGroupName = name,
                                    rememberForSimilarFiles = rememberRule,
                                )
                            },
                        )
                    }
                    items(selectedTreeGroups, key = { it.directory.rawValue() }) { group ->
                        SafDestinationEditCard(
                            group = group,
                            onApply = { name, rememberRule ->
                                viewModel.editSafPlanDestinationGroup(
                                    groupDirectory = group.directory,
                                    newGroupName = name,
                                    rememberForSimilarFiles = rememberRule,
                                )
                            },
                        )
                    }
                }

                if (preview.accepted.isEmpty()) {
                    item { EmptyState(if (preview.filingPresentation != null) "Assign unresolved files to a project to build a reviewed plan." else "Nothing here can be acted on.") }
                }

                // Inbox Filing already renders every proposed file inside its human-facing
                // Project Home card above. Do not also allocate one generic LazyColumn item
                // per raw move below it: on large inboxes those invisible duplicate slots
                // still cost composition/measurement work.
                if (preview.filingPresentation == null) {
                    itemsIndexed(preview.accepted) { index, operation ->
                        val destructive = operation.safetyClass() == MutationSafetyClass.RED
                        val selected = index in preview.selectedIndices
                        val fallbackLabel = preview.acceptedScopeLabels.getOrElse(index) { preview.scopeLabel }
                        val groupLabel = destinationLabel(operation) ?: fallbackLabel
                        val previousLabel = preview.accepted.getOrNull(index - 1)
                            ?.let(::destinationLabel)
                            ?: preview.acceptedScopeLabels.getOrNull(index - 1)
                        Column(modifier = Modifier.animateItem()) {
                            if (index == 0 || previousLabel != groupLabel) {
                                SectionHeader(groupLabel)
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth().animateContentSize(),
                                colors = if (destructive) {
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                } else {
                                    CardDefaults.cardColors()
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(Spacing.base),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        enabled = busy == null,
                                        onCheckedChange = { checked ->
                                            viewModel.setPlanOperationSelected(index, checked)
                                        },
                                        modifier = Modifier.semantics {
                                            contentDescription = "Include: ${operationSummary(operation)}"
                                        },
                                    )
                                    OperationVisual(operation)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = operationSummary(operation), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = operation.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (destructive) {
                                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                        PlanOperationEditControls(
                                            operation = operation,
                                            onApplyMove = { destination ->
                                                viewModel.editPlanOperation(
                                                    operationIndex = index,
                                                    newDestinationPath = destination,
                                                )
                                            },
                                            onApplyRename = { name ->
                                                viewModel.editPlanOperation(
                                                    operationIndex = index,
                                                    newName = name,
                                                )
                                            },
                                            onKeepOriginalChange = { keepOriginal ->
                                                viewModel.editPlanOperation(
                                                    operationIndex = index,
                                                    keepOriginal = keepOriginal,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                }

                if (preview.scopeNotes.isNotEmpty()) {
                    item {
                        Text(
                            text = preview.scopeNotes.joinToString(separator = " · ", prefix = "Note: "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.hairline, vertical = Spacing.hairline),
                        )
                    }
                }

                if (preview.rejected.isNotEmpty()) {
                    item { SectionHeader("Not included") }
                    items(preview.rejected) { rejected ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(Spacing.base)) {
                                Text(
                                    text = operationSummary(rejected.operation),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = rejected.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            ActionRow {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.approvePlan(preview)
                    },
                    enabled = preview.selectedIndices.isNotEmpty() && busy == null && filingInventory?.complete != false,
                ) {
                    val selectedFileCount = preview.selectedIndices.count { index ->
                        when (preview.accepted.getOrNull(index)) {
                            is PlannedOperation.Move, is PlannedOperation.Copy, is PlannedOperation.Rename, is PlannedOperation.Trash -> true
                            else -> false
                        }
                    }
                    Text("Run ${if (preview.filingPresentation != null) selectedFileCount else preview.selectedIndices.size}")
                }
                TextButton(
                    onClick = { viewModel.exportReviewedPlan(preview) },
                    enabled = preview.selectedIndices.isNotEmpty(),
                ) {
                    Text("Export")
                }
                TextButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }

    if (confirmBulkRed && preview != null) {
        val redCount = preview.accepted.count {
            it.safetyClass() == MutationSafetyClass.RED
        }
        AlertDialog(
            onDismissRequest = { confirmBulkRed = false },
            title = { Text("Include $redCount quarantine action(s)?") },
            text = {
                Text(
                    "These actions move files to Pocket Steward's recoverable Trash. " +
                        "Nothing is permanently deleted, but the files leave their current folders. " +
                        "You can still deselect any row before Run.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.selectAllPlanOperations()
                        confirmBulkRed = false
                    },
                ) {
                    Text("Include all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkRed = false }) {
                    Text("Keep unchecked")
                }
            },
        )
    }
}

@Composable
private fun PlanOperationEditControls(
    operation: PlannedOperation,
    onApplyMove: (String) -> Unit,
    onApplyRename: (String) -> Unit,
    onKeepOriginalChange: (Boolean) -> Unit,
) {
    var editing by remember(operation) { mutableStateOf(false) }

    when (operation) {
        is PlannedOperation.Move,
        is PlannedOperation.Copy,
        -> {
            val directDestination = when (operation) {
                is PlannedOperation.Move -> operation.destination as? FileRef.Direct
                is PlannedOperation.Copy -> operation.destination as? FileRef.Direct
                else -> null
            }
            TextButton(
                onClick = { editing = !editing },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(if (editing) "Done editing" else "Edit action", maxLines = 1)
            }
            if (editing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = operation is PlannedOperation.Copy,
                        onCheckedChange = onKeepOriginalChange,
                    )
                    Text(
                        if (operation is PlannedOperation.Copy) "Keep original: on" else "Keep original: off",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (directDestination != null) {
                    val current = directDestination.absolutePath
                    var destination by remember(operation) { mutableStateOf(current) }
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        label = { Text("Destination file path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            onApplyMove(destination)
                            editing = false
                        },
                        enabled = destination.isNotBlank() && destination != current,
                        modifier = Modifier.padding(top = Spacing.hairline),
                    ) {
                        Text("Apply destination")
                    }
                } else {
                    Text(
                        "Destination is inside the selected tree. Edit its group above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is PlannedOperation.Rename -> {
            TextButton(onClick = { editing = !editing }) {
                Text(if (editing) "Hide editor" else "Edit new name")
            }
            if (editing) {
                var name by remember(operation) { mutableStateOf(operation.newName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New file name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onApplyRename(name)
                        editing = false
                    },
                    enabled = name.isNotBlank() && name != operation.newName,
                    modifier = Modifier.padding(top = Spacing.hairline),
                ) {
                    Text("Apply rename edit")
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun FilingOverviewCard(filing: FilingReviewPresentation, inventory: FilingInventory, enabled: Boolean,
    onDeferRemaining: () -> Unit, onKeepRemaining: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(if (filing.reviewingUncertain) "Sort Uncertain" else "Organize Downloads", style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(inventory.entries.size).append(" reviewed items")
                    append(" · ").append(inventory.count(FilingOutcome.DESTINATION)).append(" to destinations")
                    if (inventory.count(FilingOutcome.COPY_RETAINED) > 0) append(" · ").append(inventory.count(FilingOutcome.COPY_RETAINED)).append(" copies; originals stay here")
                    append(" · ").append(inventory.count(FilingOutcome.CHECKPOINT)).append(" to Uncertain")
                    if (inventory.count(FilingOutcome.RETAINED_UNCERTAIN) > 0) append(" · ").append(inventory.count(FilingOutcome.RETAINED_UNCERTAIN)).append(" stay in Uncertain")
                    if (inventory.count(FilingOutcome.KEEP) > 0) append(" · ").append(inventory.count(FilingOutcome.KEEP)).append(" kept here")
                    if (inventory.count(FilingOutcome.BLOCKED) > 0) append(" · ").append(inventory.count(FilingOutcome.BLOCKED)).append(" blocked")
                    if (!inventory.complete) append(" · ").append(inventory.count(FilingOutcome.NEEDS_DECISION)).append(" need a decision")
                    if (filing.skippedInboxFolders > 0) append(" · ").append(filing.skippedInboxFolders).append(" existing folders left")
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
            if (filing.indexedFolderDescendantCount > 0) Text(
                "${filing.indexedFolderDescendantCount} indexed items inside reviewed folders travel with their folder; they are not separate moves.",
                style = MaterialTheme.typography.bodySmall)
            if (!inventory.complete) {
                Text("Unchecked items need an explicit choice before Run. Your selected destinations are preserved.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onDeferRemaining, enabled = enabled) { Text("Send ${inventory.pendingRefs.size} remaining to Uncertain") }
                TextButton(onClick = onKeepRemaining, enabled = enabled) { Text("Keep ${inventory.pendingRefs.size} remaining here") }
            }
            Text(
                if (filing.reviewingUncertain) {
                    "Review project matches from the checkpoint. Anything still unresolved stays exactly where it is."
                } else if (filing.skippedInboxFolders > 0) {
                    "Loose files are grouped by project or moved to Uncertain. Existing folders stay put until they can be reviewed safely."
                } else {
                    "Files are grouped by project. Unmatched files go to the inbox's Uncertain folder for later review."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
        }
    }
}

@Composable
private fun FilingDestinationCard(
    group: com.pocketsteward.app.filing.FilingReviewGroup,
    selectionEnabled: Boolean,
    preview: ScanUiState.PlanPreview,
    sourceOperationIndices: Map<String, Int>,
    onSetSelected: (Int, Boolean) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDefer: (Set<String>) -> Unit,
    onKeep: (Set<String>) -> Unit,
    inventoryBySource: Map<String, com.pocketsteward.app.filing.FilingInventoryEntry>,
    onAssign: (Set<String>, String, com.pocketsteward.app.filing.FilingRole, String?, String?) -> Unit,
    knownProjects: List<com.pocketsteward.app.filing.ProjectHomeCandidate>,
    onApplyDestination: (String, String) -> Unit,
) {
    var expanded by remember(group.destinationPath) { mutableStateOf(false) }
    var editing by remember(group.destinationPath) { mutableStateOf(false) }
    var destinationRoot by remember(group.destinationPath) {
        mutableStateOf(group.destinationPath.substringBeforeLast('/', missingDelimiterValue = group.projectHomePath))
    }
    var groupName by remember(group.destinationPath) {
        mutableStateOf(group.destinationPath.substringAfterLast('/'))
    }
    val totalBytes = group.items.sumOf { it.sizeBytes }
    val selectedFiles = group.items.count { item ->
        sourceOperationIndices[item.sourceRef]?.let { it in preview.selectedIndices } == true
    }

    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (group.isUncertainCheckpoint) "Uncertain checkpoint" else if (group.isTopicDestination) "Media category" else {
                            group.destinationPath.removePrefix(group.projectHomePath).trim('/').replace("/", " › ")
                                .ifBlank { "Project root" }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${preview.scopes.firstOrNull()?.label ?: "Inbox"} → ${friendlyPath(group.destinationPath)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(if (group.isUncertainCheckpoint) "Review later" else if (group.isTopicDestination) "Confirm topic" else if (group.existingProjectHome) "Existing project" else "New project home")
                            group.release?.let { append(" · release ").append(it) }
                            append(" · ").append(selectedFiles).append("/").append(group.items.size).append(" selected")
                            append(" · ").append(formatBytes(totalBytes))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.hairline),
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Files")
                }
            }

            if (selectedFiles < group.items.size) {
                val unselected = group.items.filter { item -> sourceOperationIndices[item.sourceRef]?.let { it in preview.selectedIndices } != true }
                    .mapTo(hashSetOf()) { it.sourceRef }
                OutlinedButton(onClick = { onConfirm(unselected) }, enabled = selectionEnabled) {
                    Text(if (group.isUncertainCheckpoint) "Confirm checkpoint moves" else "Confirm destination for group")
                }
                if (!group.isUncertainCheckpoint) TextButton(onClick = { onDefer(unselected) }, enabled = selectionEnabled) {
                    Text("Send unselected to Uncertain")
                }
                TextButton(onClick = { onKeep(unselected) }, enabled = selectionEnabled) { Text("Keep unselected here") }
            }

            if (expanded) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(group.items, key = { it.sourceRef }) { item ->
                        val operationIndex = sourceOperationIndices[item.sourceRef]
                        val checked = operationIndex?.let { it in preview.selectedIndices } == true
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { value -> operationIndex?.let { onSetSelected(it, value) } },
                                enabled = selectionEnabled && operationIndex != null,
                            )
                            FileVisual(name = item.displayName, isDirectory = item.isDirectory, location = item.sourceRef, size = 28.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                                inventoryBySource[item.sourceRef]?.let { Text(it.reason, style = MaterialTheme.typography.labelSmall) }
                                Text(
                                    buildString {
                                        append(if (group.isUncertainCheckpoint) "Uncertain · review later" else if (item.confidence == FilingConfidence.STRONG) "Strong match" else "Probable match · review")
                                        append(" · ").append(formatBytes(item.sizeBytes))
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.confidence == FilingConfidence.STRONG) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    },
                                )
                                item.evidence.take(3).forEach { reason ->
                                    Text(
                                        "• $reason",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!checked) {
                                    if (!group.isUncertainCheckpoint) TextButton(onClick = { onDefer(setOf(item.sourceRef)) }, enabled = selectionEnabled) { Text("Move to Uncertain") }
                                    TextButton(onClick = { onKeep(setOf(item.sourceRef)) }, enabled = selectionEnabled) { Text("Keep here") }
                                }
                            }
                        }
                    }
                }
            }

            var assigning by remember(group.destinationPath) { mutableStateOf(false) }
            TextButton(onClick = { assigning = !assigning }) { Text(if (assigning) "Hide project assignment" else "Assign project / role") }
            var assignSelectedOnly by remember(group.destinationPath) { mutableStateOf(false) }
            if (assigning) {
                TextButton(onClick = { assignSelectedOnly = !assignSelectedOnly }) {
                    Text(if (assignSelectedOnly) "Scope: selected files only · switch to entire group" else "Scope: entire group · switch to selected files")
                }
                val assignmentItems = if (assignSelectedOnly) group.items.filter { item ->
                    sourceOperationIndices[item.sourceRef]?.let { it in preview.selectedIndices } == true
                } else group.items
                FilingAssignmentEditor(assignmentItems.size, assignmentItems.take(3).joinToString(" · ") { it.displayName }, knownProjects) { title, role, home, release ->
                    onAssign(assignmentItems.mapTo(hashSetOf()) { it.sourceRef }, title, role, home, release)
                }
            }

            if (group.editableDirectDestination) {
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "Hide destination editor" else "Edit destination")
                }
            }
            if (editing && group.editableDirectDestination) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(if (group.release != null) "Release folder" else "Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = destinationRoot,
                    onValueChange = { destinationRoot = it },
                    label = { Text(if (group.release != null) "Project home" else "Parent folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                )
                Button(
                    onClick = {
                        onApplyDestination(destinationRoot, groupName)
                        editing = false
                    },
                    enabled = destinationRoot.isNotBlank() && groupName.isNotBlank(),
                    modifier = Modifier.padding(top = Spacing.tight),
                ) {
                    Text("Apply destination")
                }
            }
        }
    }
}

private fun friendlyPath(path: String): String {
    val normalized = path.trimEnd('/')
    val internalPrefix = "/storage/emulated/0"
    return when {
        normalized == internalPrefix -> "Internal storage"
        normalized.startsWith("$internalPrefix/") -> {
            val relative = normalized.removePrefix("$internalPrefix/")
            "Internal storage › " + relative.split('/').filter(String::isNotBlank).joinToString(" › ")
        }
        normalized.startsWith("content://") -> "Selected storage › " + normalized.substringAfterLast('/')
        else -> normalized.split('/').filter(String::isNotBlank).takeLast(4).joinToString(" › ")
    }
}

private data class DestinationEditGroup(
    val directory: String,
    val root: String,
    val groupName: String,
)

@Composable
private fun DestinationEditCard(
    group: DestinationEditGroup,
    onApply: (root: String, groupName: String, rememberRule: Boolean) -> Unit,
) {
    var root by remember(group.directory) { mutableStateOf(group.root) }
    var groupName by remember(group.directory) { mutableStateOf(group.groupName) }
    var rememberRule by remember(group.directory) { mutableStateOf(false) }
    var editing by remember(group.directory) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = group.groupName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = friendlyPath(group.directory),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
            TextButton(onClick = { editing = !editing }) {
                Text(if (editing) "Hide editor" else "Edit destination")
            }
            if (editing) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = root,
                    onValueChange = { root = it },
                    label = { Text("Parent folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight)) {
                    Checkbox(
                        checked = rememberRule,
                        onCheckedChange = { rememberRule = it },
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = Spacing.hairline)) {
                        Text("Remember this correction")
                        Text(
                            "Reuse this destination when a clear shared filename term appears later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = {
                        onApply(root, groupName, rememberRule)
                        editing = false
                    },
                    enabled = root.isNotBlank() && groupName.isNotBlank(),
                    modifier = Modifier.padding(top = Spacing.tight),
                ) {
                    Text("Apply destination")
                }
            }
        }
    }
}

private fun destinationEditGroup(operation: PlannedOperation): DestinationEditGroup? {
    val directory = when (operation) {
        is PlannedOperation.Move -> {
            val destination = operation.destination as? FileRef.Direct ?: return null
            destination.absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        }
        is PlannedOperation.Copy -> {
            val destination = operation.destination as? FileRef.Direct ?: return null
            destination.absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        }
        else -> return null
    }.trimEnd('/')

    val root = directory.substringBeforeLast('/', missingDelimiterValue = "")
    val groupName = directory.substringAfterLast('/')
    if (root.isBlank() || groupName.isBlank()) return null
    return DestinationEditGroup(directory, root, groupName)
}

private data class SafDestinationEditGroup(
    val directory: FileRef.Child,
    val displayPath: String,
    val groupName: String,
)

@Composable
private fun SafDestinationEditCard(
    group: SafDestinationEditGroup,
    onApply: (groupName: String, rememberRule: Boolean) -> Unit,
) {
    var groupName by remember(group.directory.rawValue()) { mutableStateOf(group.groupName) }
    var rememberRule by remember(group.directory.rawValue()) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = group.displayPath,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Inside selected Android folder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
            ) {
                Checkbox(
                    checked = rememberRule,
                    onCheckedChange = { rememberRule = it },
                )
                Column(modifier = Modifier.weight(1f).padding(start = Spacing.hairline)) {
                    Text("Remember this correction")
                    Text(
                        "Reuse this group when a clear shared filename term appears later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = { onApply(groupName, rememberRule) },
                enabled = groupName.isNotBlank() && groupName != group.groupName,
                modifier = Modifier.padding(top = Spacing.tight),
            ) {
                Text("Apply group edit")
            }
        }
    }
}

private fun safDestinationEditGroup(operation: PlannedOperation): SafDestinationEditGroup? {
    val directory = when (operation) {
        is PlannedOperation.CreateDirectory ->
            operation.parent.child(operation.name) as? FileRef.Child
        is PlannedOperation.Move ->
            operation.destination.knownParentOrNull() as? FileRef.Child
        is PlannedOperation.Copy ->
            operation.destination.knownParentOrNull() as? FileRef.Child
        else -> null
    } ?: return null

    return SafDestinationEditGroup(
        directory = directory,
        displayPath = directory.selectedTreeRelativePath(),
        groupName = directory.name,
    )
}

private fun FileRef.Child.selectedTreeRelativePath(): String {
    val segments = mutableListOf<String>()
    var current: FileRef = this
    while (current is FileRef.Child) {
        segments += current.name
        current = current.parent
    }
    return segments.asReversed().joinToString("/")
}

private fun destinationLabel(operation: PlannedOperation): String? =
    destinationEditGroup(operation)?.directory
        ?: safDestinationEditGroup(operation)?.displayPath


private enum class PlanView(val label: String) { LIST("List"), NOW("Now"), AFTER("After") }

/** The file an operation acts on, drawn the same way as everywhere else in the app. */
@Composable
private fun OperationVisual(operation: PlannedOperation) {
    val source = when (operation) {
        is PlannedOperation.Move -> operation.source
        is PlannedOperation.Copy -> operation.source
        is PlannedOperation.Rename -> operation.source
        is PlannedOperation.Trash -> operation.source
        is PlannedOperation.CreateDirectory, is PlannedOperation.WriteTextFile -> null
    }
    when {
        source != null -> FileVisual(
            name = PlanTree.segments(source).lastOrNull().orEmpty(),
            // A Child ref names something that does not exist yet.
            location = source.takeIf { it !is FileRef.Child }?.rawValue(),
        )
        operation is PlannedOperation.CreateDirectory ->
            FileVisual(name = operation.name, location = null, isDirectory = true)
        operation is PlannedOperation.WriteTextFile ->
            FileVisual(name = operation.name, location = null)
    }
}

@Composable
private fun PlanTreeView(
    tree: PlanTree.Tree,
    after: Boolean,
    emptyMessage: String?,
    modifier: Modifier = Modifier,
) {
    if (emptyMessage != null) {
        EmptyState(emptyMessage, modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            Text(
                text = tree.rootLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = Spacing.tight),
            )
        }
        itemsIndexed(tree.rows) { _, row ->
            PlanTreeRow(row, after)
        }
        if (tree.hiddenRows > 0) {
            item {
                Text(
                    text = "${tree.hiddenRows} more rows not drawn. The List view still has every action.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.tight),
                )
            }
        }
    }
}
@Composable
private fun PlanTreeRow(row: PlanTree.Row, after: Boolean) {
    val markLabel = row.mark?.let { treeMarkLabel(it, after) }
    val spoken = buildString {
        append(if (row.isFolder) "Folder " else "File ")
        append(row.name)
        if (row.isFolder) append(", ${row.fileCount} file(s)")
        markLabel?.let { append(", $it") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 16).dp, top = 3.dp, bottom = 3.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        FileVisual(name = row.name, location = null, isDirectory = row.isFolder, size = 24.dp)
        Text(
            text = row.name,
            style = if (row.isFolder) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.isFolder && row.fileCount > 0) {
            Text(
                text = "${row.fileCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.mark != null && markLabel != null) {
            TreeMarkChip(markLabel, row.mark)
        }
    }
}

@Composable
private fun TreeMarkChip(label: String, mark: PlanTree.Mark) {
    val (bg, fg) = when (mark) {
        PlanTree.Mark.TRASH -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        PlanTree.Mark.NEW -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun treeMarkLabel(mark: PlanTree.Mark, after: Boolean): String = when (mark) {
    PlanTree.Mark.MOVE -> if (after) "arrives" else "moves"
    PlanTree.Mark.RENAME -> if (after) "new name" else "renamed"
    PlanTree.Mark.COPY -> if (after) "copy" else "copied"
    PlanTree.Mark.TRASH -> if (after) "recoverable" else "to Trash"
    PlanTree.Mark.NEW -> "new"
}

@Composable
private fun FilingAssignmentEditor(
    count: Int,
    examples: String,
    knownProjects: List<com.pocketsteward.app.filing.ProjectHomeCandidate>,
    onApply: (String, com.pocketsteward.app.filing.FilingRole, String?, String?) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var releaseFolder by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(com.pocketsteward.app.filing.FilingRole.AUTO) }
    var chosenHomePath by rememberSaveable { mutableStateOf<String?>(null) }
    var showChoices by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base), verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
            Text("Assign $count files together", style = MaterialTheme.typography.titleMedium)
            Text(examples.ifBlank { "No files match this search." }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(title, { title = it; chosenHomePath = null }, label = { Text("Project title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(releaseFolder, { releaseFolder = it }, label = { Text("Release folder (optional)") }, supportingText = { Text("Keeps this batch together, for example v1.4.0 or Draft 3. Blank uses normal filing.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { showChoices = !showChoices }) { Text(if (showChoices) "Hide project choices" else "Choose a known project / role") }
            if (showChoices) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(com.pocketsteward.app.filing.ProjectHomeRanking.rank(knownProjects, title).take(12), key = { "home:${it.path}" }) { home ->
                        TextButton(onClick = { title = home.name; chosenHomePath = home.path }) {
                            Column { Text(home.name); Text(home.path, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    items(com.pocketsteward.app.filing.FilingRole.entries, key = { "role:${it.name}" }) { choice ->
                        TextButton(onClick = { role = choice }) { Text(choice.label) }
                    }
                }
            }
            Text("Role: ${role.label}. Existing folders remain intact. This changes the preview; files move only after approval.", style = MaterialTheme.typography.bodySmall)
            chosenHomePath?.let { Text("Selected home: $it", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { onApply(title, role, chosenHomePath, releaseFolder.trim().takeIf { it.isNotEmpty() }) }, enabled = count > 0 && title.isNotBlank()) { Text("Apply assignment to preview") }
        }
    }
}
