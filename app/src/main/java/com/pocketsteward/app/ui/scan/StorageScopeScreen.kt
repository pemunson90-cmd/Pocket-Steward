package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScopeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: ScanViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ScanViewModel(container.settingsRepository, container) }
        },
    )

    val accessState by container.settingsRepository.storageAccessState.collectAsState(initial = null)
    val uiState by viewModel.uiState.collectAsState()

    val targets = when (accessState?.mode) {
        StorageAccessMode.DIRECT -> listOf(
            ScanTarget.Downloads, ScanTarget.Documents, ScanTarget.Pictures, ScanTarget.Everything,
        )
        StorageAccessMode.SAF -> listOf(ScanTarget.GrantedFolder("Granted folder"))
        null -> emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (val state = uiState) {
                is ScanUiState.Idle -> TargetList(targets, onTargetSelected = viewModel::startScan)
                is ScanUiState.Scanning -> ScanningState(state)
                is ScanUiState.Summary -> ScanSummaryContent(
                    state = state,
                    onScanAgain = viewModel::reset,
                    onOrganizeApks = { viewModel.proposeOrganizeApks(state) },
                )
                is ScanUiState.PlanPreview -> PlanPreviewContent(
                    state = state,
                    onApprove = { viewModel.approvePlan(state) },
                    onCancel = viewModel::reset,
                )
                is ScanUiState.ExecutionDone -> ExecutionDoneContent(
                    state = state,
                    onUndo = { viewModel.undoTask(state.summary.taskRunId) },
                    onDone = viewModel::reset,
                )
                is ScanUiState.Undoing -> UndoingContent()
                is ScanUiState.UndoDone -> UndoDoneContent(state, onDone = viewModel::reset)
                is ScanUiState.Error -> ErrorState(state.message, onRetry = viewModel::reset)
            }
        }
    }
}

@Composable
private fun TargetList(targets: List<ScanTarget>, onTargetSelected: (ScanTarget) -> Unit) {
    if (targets.isEmpty()) {
        Text("No storage access granted yet.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(targets) { target ->
            Card(onClick = { onTargetSelected(target) }) {
                Text(text = target.label, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun ScanningState(state: ScanUiState.Scanning) {
    Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = "Scanned ${state.progress.processedCount} items",
            modifier = Modifier.padding(top = 16.dp),
        )
        state.progress.currentDirectoryName?.let {
            Text(text = it, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ScanSummaryContent(state: ScanUiState.Summary, onScanAgain: () -> Unit, onOrganizeApks: () -> Unit) {
    Column {
        Text(text = state.scopeLabel)
        Text(text = "${state.totalFiles} files · ${formatBytes(state.totalBytes)}")

        LazyColumn(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(FileCategory.entries) { category ->
                val stat = state.byCategory[category]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = category.name.lowercase().replaceFirstChar { it.uppercase() })
                    Text(text = "${stat?.fileCount ?: 0}")
                }
            }
        }

        val apkCount = state.byCategory[FileCategory.APK]?.fileCount ?: 0
        if (apkCount > 0) {
            // Milestone 2's own exercise of create+move+validator+executor+
            // journal — a hard-coded plan, not a real "organize" feature.
            // Real request-driven planning starts at Milestone 4/5.
            Card(onClick = onOrganizeApks, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = "Test: move $apkCount APK(s) into an APKs subfolder", modifier = Modifier.padding(16.dp))
            }
        }

        Card(onClick = onScanAgain, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Scan again", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun PlanPreviewContent(state: ScanUiState.PlanPreview, onApprove: () -> Unit, onCancel: () -> Unit) {
    // weight(1f) is load-bearing: without it this LazyColumn takes every
    // remaining pixel of the Column and pushes the Approve/Cancel row past
    // the bottom of the screen, with no outer scroll to reach it. A short
    // plan hides the bug because a lazy list shorter than its max
    // constraint sizes to content — any future long list in a plain Column
    // has the same exposure.
    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = state.goal)
        Text(
            text = "${state.accepted.size} action(s) ready" +
                if (state.rejected.isNotEmpty()) ", ${state.rejected.size} left untouched" else "",
        )

        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.accepted) { operation ->
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = operationSummary(operation))
                        Text(text = operation.reason)
                    }
                }
            }
            if (state.rejected.isNotEmpty()) {
                item {
                    Text(text = "Left untouched", modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(state.rejected) { rejected ->
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = operationSummary(rejected.operation))
                        Text(text = rejected.reason)
                    }
                }
            }
        }

        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(onClick = onApprove) { Text(text = "Approve", modifier = Modifier.padding(16.dp)) }
            Card(onClick = onCancel) { Text(text = "Cancel", modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun ExecutionDoneContent(
    state: ScanUiState.ExecutionDone,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    val summary = state.summary
    Column {
        Text(text = "Task complete")
        if (summary.foldersCreated > 0) Text(text = "${summary.foldersCreated} folder(s) created")
        if (summary.filesMoved > 0) Text(text = "${summary.filesMoved} moved")
        if (summary.filesRenamed > 0) Text(text = "${summary.filesRenamed} renamed")
        if (summary.filesTrashed > 0) Text(text = "${summary.filesTrashed} trashed")
        if (summary.failed > 0) Text(text = "${summary.failed} failed")
        if (summary.leftUntouched.isNotEmpty()) Text(text = "${summary.leftUntouched.size} left untouched")

        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (summary.succeededTotal > 0) {
                Card(onClick = onUndo) {
                    Text(text = "Undo task", modifier = Modifier.padding(16.dp))
                }
            }
            Card(onClick = onDone) {
                Text(text = "Done", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun UndoingContent() {
    Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(text = "Restoring files…", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun UndoDoneContent(state: ScanUiState.UndoDone, onDone: () -> Unit) {
    val summary = state.summary
    Column {
        Text(text = if (summary.complete) "Undo complete" else "Undo needs attention")
        Text(text = "${summary.undone} restored")
        if (summary.blocked > 0) Text(text = "${summary.blocked} blocked")
        if (summary.skipped > 0) Text(text = "${summary.skipped} skipped")
        summary.messages.take(5).forEach { message ->
            Text(text = message, modifier = Modifier.padding(top = 6.dp))
        }
        Card(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Done", modifier = Modifier.padding(16.dp))
        }
    }
}

private fun operationSummary(operation: PlannedOperation): String = when (operation) {
    is PlannedOperation.CreateDirectory -> "Create folder: ${operation.name}"
    is PlannedOperation.Move -> "Move ${operation.source.shortPath()} → ${operation.destination.shortPath()}"
    is PlannedOperation.Rename -> "Rename to ${operation.newName}"
    is PlannedOperation.Trash -> "Trash ${operation.source.shortPath()}"
}

/**
 * The last two path segments (parent folder + filename), not just the
 * filename: a move's source and destination usually share a filename, so
 * showing only the basename made every row in the preview read
 * "X.apk → X.apk" with no way to tell what actually changed.
 */
private fun FileRef.shortPath(): String = rawValue().split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Text(text = "Scan failed: $message")
        Card(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Try again", modifier = Modifier.padding(16.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}
