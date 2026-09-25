package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

    val preview = state
    var confirmBulkRed by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(PlanView.LIST) }
    val haptics = LocalHapticFeedback.current

    ScanFlowScaffold(
        title = "Review changes",
        onBack = onBack,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
    ) { contentModifier ->
        if (preview == null) {
            EmptyState("Nothing proposed.", contentModifier)
            return@ScanFlowScaffold
        }

        Column(modifier = contentModifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight)) {
                Text(
                    text = preview.goal,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = buildString {
                        val selectedFiles = preview.selectedIndices.count { index ->
                            when (preview.accepted.getOrNull(index)) {
                                is PlannedOperation.Move, is PlannedOperation.Copy, is PlannedOperation.Rename, is PlannedOperation.Trash -> true
                                else -> false
                            }
                        }
                        if (preview.filingPresentation != null) {
                            append("$selectedFiles selected · ${preview.filingPresentation.proposedCount} proposed")
                            if (preview.filingPresentation.unresolvedCount > 0) append(" · ${preview.filingPresentation.unresolvedCount} stays")
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
                            "Select them deliberately, or use All if you have reviewed the whole set.",
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
                        selected = view == option,
                        onClick = { view = option },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = PlanView.entries.size),
                    ) {
                        Text(option.label)
                    }
                }
            }

            if (view == PlanView.LIST) Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                OutlinedButton(
                    onClick = viewModel::selectRecommendedPlanOperations,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (preview.filingPresentation != null) "Strong only" else "Safe only")
                }
                OutlinedButton(
                    onClick = {
                        if (redCount > 0) {
                            confirmBulkRed = true
                        } else {
                            viewModel.selectAllPlanOperations()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Select all")
                }
                OutlinedButton(
                    onClick = viewModel::clearPlanSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
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
                    modifier = Modifier.weight(1f),
                )
            } else LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
                contentPadding = PaddingValues(bottom = Spacing.section),
            ) {
                val destinationGroups = preview.accepted
                    .mapNotNull(::destinationEditGroup)
                    .distinctBy { it.directory }
                val selectedTreeGroups = preview.accepted
                    .mapNotNull(::safDestinationEditGroup)
                    .distinctBy { it.directory.rawValue() }

                preview.filingPresentation?.let { filing ->
                    item {
                        FilingOverviewCard(filing)
                    }
                    items(filing.groups, key = { it.destinationPath }) { group ->
                        FilingDestinationCard(
                            group = group,
                            preview = preview,
                            onSetSelected = viewModel::setPlanOperationSelected,
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
                    if (filing.unresolved.isNotEmpty()) {
                        item { SectionHeader("Stays in inbox") }
                        items(filing.unresolved, key = { it.sourceRef }) { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(Spacing.base),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FileVisual(name = item.displayName, location = item.sourceRef)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            item.evidence.firstOrNull() ?: "Not enough project evidence to move safely.",
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

                // Spec 6b: a protection that applies silently is
                // indistinguishable from a bug. Whatever the plan source
                // declined to touch is stated above the row list, because a
                // 3,000-row list is not read.
                if (preview.scopeNotes.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(Spacing.base)) {
                                Text("Plan notes", style = MaterialTheme.typography.titleSmall)
                                preview.scopeNotes.forEach { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = Spacing.hairline),
                                    )
                                }
                            }
                        }
                    }
                }

                if (preview.accepted.isEmpty()) {
                    item { EmptyState("Nothing here can be acted on.") }
                }

                itemsIndexed(preview.accepted) { index, operation ->
                    if (preview.filingPresentation != null && operation is PlannedOperation.CreateDirectory) {
                        return@itemsIndexed
                    }
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
                    enabled = preview.selectedIndices.isNotEmpty(),
                ) {
                    val selectedFileCount = preview.selectedIndices.count { index ->
                        when (preview.accepted.getOrNull(index)) {
                            is PlannedOperation.Move, is PlannedOperation.Copy, is PlannedOperation.Rename, is PlannedOperation.Trash -> true
                            else -> false
                        }
                    }
                    Text("Run ${if (preview.filingPresentation != null) selectedFileCount else preview.selectedIndices.size} selected")
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.hairline),
            ) {
                Checkbox(
                    checked = operation is PlannedOperation.Copy,
                    onCheckedChange = onKeepOriginalChange,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep original")
                    Text(
                        if (operation is PlannedOperation.Copy) {
                            "This action copies the file; the source stays where it is."
                        } else {
                            "Off means move. Turn on to copy instead."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val directDestination = when (operation) {
                is PlannedOperation.Move -> operation.destination as? FileRef.Direct
                is PlannedOperation.Copy -> operation.destination as? FileRef.Direct
                else -> null
            }
            if (directDestination != null) {
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "Hide editor" else "Edit destination")
                }
                if (editing) {
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
                        Text("Apply destination edit")
                    }
                }
            } else {
                Text(
                    "Destination is inside the selected tree. Edit its group in Destinations above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
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
private fun FilingOverviewCard(filing: FilingReviewPresentation) {
    val strong = filing.groups.flatMap { it.items }.count { it.confidence == FilingConfidence.STRONG }
    val probable = filing.groups.flatMap { it.items }.count { it.confidence == FilingConfidence.PROBABLE }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text("Inbox filing", style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(strong).append(" strong")
                    if (probable > 0) append(" · ").append(probable).append(" probable")
                    if (filing.unresolvedCount > 0) append(" · ").append(filing.unresolvedCount).append(" staying put")
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
            Text(
                "Strong matches are checked. Probable matches wait for you. File type never outranks project ownership.",
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
    preview: ScanUiState.PlanPreview,
    onSetSelected: (Int, Boolean) -> Unit,
    onApplyDestination: (String, String) -> Unit,
) {
    var expanded by remember(group.destinationPath) { mutableStateOf(true) }
    var editing by remember(group.destinationPath) { mutableStateOf(false) }
    var destinationRoot by remember(group.destinationPath) {
        mutableStateOf(group.destinationPath.substringBeforeLast('/', missingDelimiterValue = group.projectHomePath))
    }
    var groupName by remember(group.destinationPath) {
        mutableStateOf(group.destinationPath.substringAfterLast('/'))
    }
    val totalBytes = group.items.sumOf { it.sizeBytes }
    val selectedFiles = group.items.count { item ->
        operationIndexForSource(preview, item.sourceRef)?.let { it in preview.selectedIndices } == true
    }

    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.projectName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        friendlyPath(group.destinationPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(if (group.existingProjectHome) "Existing project" else "New project home")
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

            if (expanded) {
                group.items.forEach { item ->
                    val operationIndex = operationIndexForSource(preview, item.sourceRef)
                    val checked = operationIndex?.let { it in preview.selectedIndices } == true
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { value -> operationIndex?.let { onSetSelected(it, value) } },
                            enabled = operationIndex != null,
                        )
                        FileVisual(name = item.displayName, location = item.sourceRef, size = 28.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (item.confidence == FilingConfidence.STRONG) "Strong match" else "Probable match · review",
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
                        }
                    }
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

private fun operationIndexForSource(preview: ScanUiState.PlanPreview, sourceRef: String): Int? {
    val index = preview.accepted.indexOfFirst { operation ->
        when (operation) {
            is PlannedOperation.Move -> operation.source.rawValue() == sourceRef
            is PlannedOperation.Copy -> operation.source.rawValue() == sourceRef
            is PlannedOperation.Rename -> operation.source.rawValue() == sourceRef
            is PlannedOperation.Trash -> operation.source.rawValue() == sourceRef
            else -> false
        }
    }
    return index.takeIf { it >= 0 }
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