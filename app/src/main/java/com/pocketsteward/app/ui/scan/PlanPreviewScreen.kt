package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.plan.PlannedOperation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
            ScreenHeadline(
                text = preview.goal,
                supporting = buildString {
                    append("${preview.selectedIndices.size} selected · ${preview.accepted.size} available")
                    if (preview.rejected.isNotEmpty()) append(" · ${preview.rejected.size} not included")
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                OutlinedButton(
                    onClick = viewModel::selectSafePlanOperations,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Safe only")
                }
                OutlinedButton(
                    onClick = viewModel::selectAllPlanOperations,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("All")
                }
                OutlinedButton(
                    onClick = viewModel::clearPlanSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                val destinationGroups = preview.accepted
                    .mapNotNull(::destinationEditGroup)
                    .distinctBy { it.directory }

                if (destinationGroups.isNotEmpty()) {
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
                }

                // Spec 6b: a protection that applies silently is
                // indistinguishable from a bug. Whatever the plan source
                // declined to touch is stated above the row list, because a
                // 3,000-row list is not read.
                if (preview.scopeNotes.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(Spacing.base)) {
                                Text("Not included", style = MaterialTheme.typography.titleSmall)
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
                    val destructive = operation.safetyClass() == MutationSafetyClass.RED
                    val selected = index in preview.selectedIndices
                    val fallbackLabel = preview.acceptedScopeLabels.getOrElse(index) { preview.scopeLabel }
                    val groupLabel = destinationLabel(operation) ?: fallbackLabel
                    val previousLabel = preview.accepted.getOrNull(index - 1)
                        ?.let(::destinationLabel)
                        ?: preview.acceptedScopeLabels.getOrNull(index - 1)
                    Column {
                        if (index == 0 || previousLabel != groupLabel) {
                            SectionHeader(groupLabel)
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (destructive) {
                                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                            } else {
                                CardDefaults.cardColors()
                            },
                        ) {
                            Row(modifier = Modifier.padding(Spacing.base)) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        viewModel.setPlanOperationSelected(index, checked)
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = operationSummary(operation), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = operation.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    onClick = { viewModel.approvePlan(preview) },
                    enabled = preview.selectedIndices.isNotEmpty(),
                ) {
                    Text("Run ${preview.selectedIndices.size} selected")
                }
                OutlinedButton(
                    onClick = { viewModel.exportReviewedPlan(preview) },
                    enabled = preview.selectedIndices.isNotEmpty(),
                ) {
                    Text("Export plan")
                }
                OutlinedButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PlanOperationEditControls(
    operation: PlannedOperation,
    onApplyMove: (String) -> Unit,
    onApplyRename: (String) -> Unit,
) {
    var editing by remember(operation) { mutableStateOf(false) }

    when (operation) {
        is PlannedOperation.Move -> {
            TextButton(onClick = { editing = !editing }) {
                Text(if (editing) "Hide editor" else "Edit destination")
            }
            if (editing) {
                val current = (operation.destination as? FileRef.Direct)?.absolutePath.orEmpty()
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
                    Text("Apply move edit")
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = group.directory,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
            )
            OutlinedTextField(
                value = root,
                onValueChange = { root = it },
                label = { Text("Destination root") },
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
                        "When there is a clear shared filename term, reuse this group for similar files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = { onApply(root, groupName, rememberRule) },
                enabled = root.isNotBlank() && groupName.isNotBlank(),
                modifier = Modifier.padding(top = Spacing.tight),
            ) {
                Text("Apply destination")
            }
        }
    }
}

private fun destinationEditGroup(operation: PlannedOperation): DestinationEditGroup? {
    val directory = when (operation) {
        is PlannedOperation.CreateDirectory -> {
            val parent = operation.parent as? FileRef.Direct ?: return null
            "${parent.absolutePath.trimEnd('/')}/${operation.name}"
        }
        is PlannedOperation.Move -> {
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

private fun destinationLabel(operation: PlannedOperation): String? =
    destinationEditGroup(operation)?.directory

