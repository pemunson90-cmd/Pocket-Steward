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
import androidx.compose.material3.Text
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

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
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
                    val scopeLabel = preview.acceptedScopeLabels.getOrElse(index) { preview.scopeLabel }
                    Column {
                        if (index == 0 || preview.acceptedScopeLabels.getOrNull(index - 1) != scopeLabel) {
                            SectionHeader(scopeLabel)
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
                OutlinedButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}
