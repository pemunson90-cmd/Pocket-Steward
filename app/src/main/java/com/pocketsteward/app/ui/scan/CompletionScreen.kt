package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.pocketsteward.app.data.db.TaskJournalProgress
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.executor.ExecutionSummary
import com.pocketsteward.app.executor.UndoSummary
import com.pocketsteward.app.ui.theme.Spacing

/**
 * What happened, once it has happened.
 *
 * `[device]` The two failures this screen was rebuilt around: a run that
 * created four folders named none of them, and "Failed" appearing as a section
 * header directly under the status "Partly done".
 */
@Composable
fun CompletionScreen(viewModel: ScanViewModel, onDone: () -> Unit) {
    val state by viewModel.completion.collectAsState()
    val undoing by viewModel.undoProgress.collectAsState()
    val taskRuns by viewModel.taskRuns.collectAsState()
    val taskProgress by viewModel.taskProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()

    ScanFlowScaffold(
        title = "Result",
        onBack = onDone,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
    ) { contentModifier ->
        val progress = undoing
        if (progress != null) {
            Column(modifier = contentModifier.fillMaxWidth()) {
                ScreenHeadline(
                    text = "Putting it back",
                    supporting = if (progress.total > 0) {
                        "Restoring ${progress.completed} of ${progress.total}"
                    } else {
                        "Reading the journal"
                    },
                )
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.completed.toFloat() / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            return@ScanFlowScaffold
        }

        when (val current = state) {
            is ScanUiState.ExecutionDone -> ExecutionDone(
                summary = current.summary,
                onUndo = { viewModel.undoTask(current.summary.taskRunId) },
                onDone = onDone,
                modifier = contentModifier,
            )
            is ScanUiState.ExecutionQueued -> ExecutionQueued(
                state = current,
                task = taskRuns.firstOrNull { it.id == current.taskRunId },
                progress = taskProgress[current.taskRunId],
                onPause = viewModel::pauseTaskExecution,
                onResume = { viewModel.resumeTaskExecution(current.taskRunId) },
                onDone = onDone,
                modifier = contentModifier,
            )
            is ScanUiState.UndoDone -> UndoDone(current.summary, onDone, contentModifier)
            else -> EmptyState("Nothing has run yet.", contentModifier)
        }
    }
}

@Composable
private fun ExecutionQueued(
    state: ScanUiState.ExecutionQueued,
    task: TaskRun?,
    progress: TaskJournalProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val total = state.operationCount.coerceAtLeast(1)
    val completed = progress?.journaledCount?.coerceAtMost(total.toLong())?.toInt() ?: 0
    val failed = progress?.failedCount ?: 0L
    val status = task?.status

    val headline = when (status) {
        TaskRunStatus.CANCELLED -> "Paused"
        TaskRunStatus.COMPLETED -> "Done"
        TaskRunStatus.PARTIAL -> "Partly done"
        TaskRunStatus.FAILED -> "Nothing went through"
        TaskRunStatus.NEEDS_REVIEW -> "Needs review"
        else -> "Working"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ScreenHeadline(
            text = headline,
            supporting = buildString {
                append("$completed of ${state.operationCount} operations recorded")
                if (failed > 0) append(" · $failed failed")
                append(" · task #${state.taskRunId}")
            },
        )

        LinearProgressIndicator(
            progress = { (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )

        Card(modifier = Modifier.fillMaxWidth().padding(top = Spacing.base)) {
            Column(modifier = Modifier.padding(Spacing.base)) {
                Text(
                    task?.requestText ?: "Approved Pocket Steward task",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    task?.summary ?: "The approved plan is journaled before each filesystem mutation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.tight),
                )
                if (status == TaskRunStatus.RUNNING || status == null) {
                    Text(
                        "You can leave this screen. Progress is durable, and Android can switch between the foreground service and background worker without replaying completed operations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.tight),
                    )
                }
            }
        }

        ActionRow {
            when (status) {
                TaskRunStatus.RUNNING, null -> {
                    OutlinedButton(onClick = onPause) { Text("Pause") }
                }
                TaskRunStatus.CANCELLED -> {
                    Button(onClick = onResume) { Text("Resume") }
                }
                else -> Unit
            }
            Button(onClick = onDone) {
                Text(
                    if (status in setOf(
                            TaskRunStatus.COMPLETED,
                            TaskRunStatus.PARTIAL,
                            TaskRunStatus.FAILED,
                            TaskRunStatus.NEEDS_REVIEW,
                        )
                    ) {
                        "Done"
                    } else {
                        "Leave running"
                    },
                )
            }
        }
    }
}
@Composable
private fun ExecutionDone(
    summary: ExecutionSummary,
    onUndo: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val headline = when {
        summary.failed == 0 -> "Done"
        summary.succeededTotal > 0 -> "Partly done"
        else -> "Nothing went through"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ScreenHeadline(
            text = headline,
            supporting = buildList {
                if (summary.foldersCreated > 0) add("${summary.foldersCreated} folder(s) created")
                if (summary.filesMoved > 0) add("${summary.filesMoved} moved")
                if (summary.filesCopied > 0) add("${summary.filesCopied} copied")
                if (summary.filesRenamed > 0) add("${summary.filesRenamed} renamed")
                if (summary.filesTrashed > 0) add("${summary.filesTrashed} moved to Trash")
                if (summary.filesWritten > 0) add("${summary.filesWritten} file(s) written")
                if (summary.failed > 0) add("${summary.failed} didn't work")
                if (summary.leftUntouched.isNotEmpty()) add("${summary.leftUntouched.size} left untouched")
            }.joinToString(" · ").ifBlank { "Nothing changed." },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            // M7 item 4. The count was the only thing that left the executor,
            // so a run that created four folders named none of them.
            if (summary.createdFolders.isNotEmpty()) {
                item { SectionHeader("Folders created") }
                items(summary.createdFolders) { path ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Spacing.base)) {
                            Text(
                                text = path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // "Failed" as a section header sat directly under the status
            // "Partly done", which read as a contradiction. The status
            // describes the run; this describes the operations in it.
            if (summary.failures.isNotEmpty()) {
                item { SectionHeader("Didn't work (${summary.failures.size})") }
                items(summary.failures) { failure ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(Spacing.base)) {
                            Text(
                                text = failure.subject,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "${failure.operationType.name.lowercase().replace('_', ' ')}: ${failure.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            if (summary.leftUntouched.isNotEmpty()) {
                item { SectionHeader("Left untouched (${summary.leftUntouched.size})") }
                items(summary.leftUntouched) { rejected ->
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
            if (summary.succeededTotal > 0) {
                OutlinedButton(onClick = onUndo) { Text("Undo this task") }
            }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun UndoDone(summary: UndoSummary, onDone: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        ScreenHeadline(
            text = if (summary.complete) "Put back" else "Undo needs attention",
            supporting = buildList {
                add("${summary.undone} restored")
                if (summary.blocked > 0) add("${summary.blocked} blocked")
                if (summary.skipped > 0) add("${summary.skipped} skipped")
            }.joinToString(" · "),
        )

        if (summary.messages.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                item { SectionHeader("Blocked") }
                items(summary.messages) { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(Spacing.base),
                        )
                    }
                }
            }
        }

        ActionRow {
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
