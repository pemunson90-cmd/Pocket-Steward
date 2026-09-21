package com.pocketsteward.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.data.db.TaskJournalProgress
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.report.ManifestFormat
import com.pocketsteward.app.report.TaskManifestDocument
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: HistoryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HistoryViewModel(
                    taskRunDao = container.database.taskRunDao(),
                    undoExecutor = container.undoExecutor,
                    manifestService = container.taskManifestService,
                    mutationRecordDao = container.database.mutationRecordDao(),
                    gatewayFor = container::gatewayFor,
                    startForegroundTask = container::startForegroundTask,
                    pauseForegroundTask = container::pauseForegroundTask,
                )
            }
        },
    )
    val tasks by viewModel.tasks.collectAsState()
    val taskProgress by viewModel.taskProgress.collectAsState()
    val actionState by viewModel.actionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (val action = actionState) {
                HistoryActionState.Idle -> Unit

                is HistoryActionState.ConfirmUndo -> {
                    ConfirmUndoCard(
                        action = action,
                        onConfirm = { viewModel.confirmUndo(action.task.id) },
                        onCancel = viewModel::dismissAction,
                    )
                }

                is HistoryActionState.Undoing -> {
                    if (action.total > 0) {
                        LinearProgressIndicator(
                            progress = { action.completed.toFloat() / action.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Restoring ${action.completed} of ${action.total}…",
                            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Reading the journal…", modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                    }
                }

                is HistoryActionState.BackgroundStarted -> {
                    Card(onClick = viewModel::dismissAction, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text("${action.message} Tap to dismiss.", modifier = Modifier.padding(12.dp))
                    }
                }

                is HistoryActionState.Done -> {
                    Card(onClick = viewModel::dismissAction, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                if (action.summary.complete) {
                                    "Undo complete: ${action.summary.undone} restored. Tap to dismiss."
                                } else {
                                    "Undo needs attention: ${action.summary.blocked} blocked. Tap to dismiss."
                                },
                            )
                            for (message in action.summary.messages.take(10)) {
                                Text(message, modifier = Modifier.padding(top = 4.dp))
                            }
                            if (action.summary.messages.size > 10) {
                                Text(
                                    "…and ${action.summary.messages.size - 10} more.",
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }

                is HistoryActionState.Manifest -> {
                    ManifestCard(
                        document = action.document,
                        exportedTo = action.exportedTo,
                        onExport = { format -> viewModel.exportManifest(action.document, format) },
                        onOpen = action.exportedTo?.let { path -> { ManifestFileActions.open(context, path) } },
                        onShowFolder = action.exportedTo?.let { path -> { ManifestFileActions.showContainingFolder(context, path) } },
                        onShare = action.exportedTo?.let { path -> { ManifestFileActions.share(context, path) } },
                        onDismiss = viewModel::dismissAction,
                        modifier = Modifier.weight(1f),
                    )
                }

                is HistoryActionState.Error -> {
                    Card(onClick = viewModel::dismissAction, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text("${action.message} Tap to dismiss.", modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (actionState is HistoryActionState.Manifest) return@Column

            if (tasks.isEmpty()) {
                Text("No tasks yet.")
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            progress = taskProgress[task.id],
                            onResume = { viewModel.resumeTask(task) },
                            onPause = viewModel::pauseTask,
                            onUndo = { viewModel.requestUndo(task) },
                            onManifest = { viewModel.showManifest(task.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmUndoCard(
    action: HistoryActionState.ConfirmUndo,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Undo ${action.operationCount} changes?")
            Text(
                "This moves every one of them back where it came from. " +
                    "Nothing is deleted either way, but it is a large change to make by accident.",
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(onClick = onConfirm) {
                    Text("Undo all ${action.operationCount}", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                Card(onClick = onCancel) {
                    Text("Cancel", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ManifestCard(
    document: TaskManifestDocument,
    exportedTo: String?,
    onExport: (ManifestFormat) -> Unit,
    onOpen: (() -> Unit)?,
    onShowFolder: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(document.title)

        if (document.hasDuplicateAssertion) {
            // The headline the whole manifest exists for. When groups and
            // kept-copies disagree it says so in its own words; this card
            // only decides how loudly to show it.
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = if (document.assertion.consistent) {
                    CardDefaults.cardColors()
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                },
            ) {
                Text(document.assertion.headline(), modifier = Modifier.padding(12.dp))
            }
        }

        Text(
            text = document.text,
            modifier = Modifier.weight(1f).padding(top = 8.dp).verticalScroll(rememberScrollState()),
        )

        exportedTo?.let {
            Text("Exported to $it", modifier = Modifier.padding(top = 8.dp))
        }

        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (exportedTo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onOpen?.let { action ->
                        Card(onClick = action) {
                            Text("Open manifest", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                    onShowFolder?.let { action ->
                        Card(onClick = action) {
                            Text("Show folder", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onShare?.let { action ->
                        Card(onClick = action) {
                            Text("Share", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                    Card(onClick = { onExport(ManifestFormat.MARKDOWN) }) {
                        Text("Export Markdown", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    Card(onClick = { onExport(ManifestFormat.JSON) }) {
                        Text("Export JSON", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(onClick = { onExport(ManifestFormat.MARKDOWN) }) {
                        Text("Export Markdown", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    Card(onClick = { onExport(ManifestFormat.JSON) }) {
                        Text("Export JSON", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
            Card(onClick = onDismiss) {
                Text("Back", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskRun,
    progress: TaskJournalProgress?,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onUndo: () -> Unit,
    onManifest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(task.requestText)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = task.status.displayName(),
                    color = task.status.statusColor(),
                )
                Text("· ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.startedAt))}")
            }
            task.summary?.takeIf { it.isNotBlank() }?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }

            val durableTotal = DurablePlanCodec.decodeOrNull(task.planJson)?.operations?.size ?: 0
            if (durableTotal > 0 && progress != null &&
                (task.status == TaskRunStatus.RUNNING || task.status == TaskRunStatus.CANCELLED)
            ) {
                val completed = progress.journaledCount.coerceAtMost(durableTotal.toLong()).toInt()
                LinearProgressIndicator(
                    progress = { completed.toFloat() / durableTotal.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    buildString {
                        append("$completed of $durableTotal operations recorded")
                        if (progress.pendingCount > 0) append(" · ${progress.pendingCount} in progress")
                        if (progress.failedCount > 0) append(" · ${progress.failedCount} failed")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (task.status == TaskRunStatus.PARTIAL) {
                // Spec item 3: a run that moved 4,829 files and missed one
                // used to read "Failed" directly above "4834 succeeded".
                Text(
                    "Most of this run succeeded. Open the manifest to see what didn't and why.",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.isResumable()) {
                    Card(onClick = onResume) {
                        Text(
                            if (task.status == TaskRunStatus.RUNNING) "Continue" else "Resume",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                if (task.status == TaskRunStatus.RUNNING && DurablePlanCodec.isDurable(task.planJson)) {
                    Card(onClick = onPause) {
                        Text("Pause", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
                if (task.status.isUndoable()) {
                    Card(onClick = onUndo) {
                        Text("Undo", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
                Card(onClick = onManifest) {
                    Text("Manifest", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }

            if (task.status == TaskRunStatus.NEEDS_REVIEW || task.status == TaskRunStatus.UNDO_PARTIAL) {
                Text("Filesystem state needs review before Pocket Steward will make another guess.", modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun TaskRun.isResumable(): Boolean =
    (status == TaskRunStatus.RUNNING || status == TaskRunStatus.CANCELLED) &&
        DurablePlanCodec.isDurable(planJson)

private fun TaskRunStatus.isUndoable(): Boolean =
    this == TaskRunStatus.COMPLETED ||
        this == TaskRunStatus.PARTIAL ||
        this == TaskRunStatus.FAILED ||
        this == TaskRunStatus.CANCELLED

@Composable
private fun TaskRunStatus.statusColor(): Color = when (this) {
    // Partial is not an error and must not be colored like one — that
    // miscoloring is half of what made the 4,829-file run unreadable.
    TaskRunStatus.FAILED, TaskRunStatus.NEEDS_REVIEW, TaskRunStatus.UNDO_PARTIAL ->
        MaterialTheme.colorScheme.error
    TaskRunStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

private fun TaskRunStatus.displayName(): String = when (this) {
    TaskRunStatus.PARTIAL -> "Partly done"
    TaskRunStatus.RUNNING -> "Interrupted · resumable"
    TaskRunStatus.CANCELLED -> "Paused · resumable"
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
