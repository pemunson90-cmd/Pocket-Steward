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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.dedupe.DuplicateGroup
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScopeScreen(onBack: () -> Unit, autoAction: PostScanAction? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: ScanViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ScanViewModel(container.settingsRepository, container) }
        },
    )

    val accessState by container.settingsRepository.storageAccessState.collectAsState(initial = null)
    val uiState by viewModel.uiState.collectAsState()

    // A Home tile lands here with an action already chosen: scan Downloads,
    // then run it. The ViewModel guards against a recomposition re-firing it.
    LaunchedEffect(autoAction) {
        if (autoAction != null) viewModel.startScanThen(ScanTarget.Downloads, autoAction)
    }

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
                is ScanUiState.Idle -> TargetList(targets, onTargetSelected = { target -> viewModel.startScan(target) })
                is ScanUiState.Scanning -> ScanningState(state)
                is ScanUiState.Summary -> ScanSummaryContent(
                    state = state,
                    onScanAgain = viewModel::reset,
                    onOrganizeApks = { viewModel.proposeOrganizeApks(state) },
                    onSmartCleanup = { includeSubfolders -> viewModel.proposeSmartCleanup(state, includeSubfolders) },
                    onFindDuplicates = { viewModel.findDuplicates(state) },
                    onFindLargestFiles = { viewModel.findLargestFiles(state) },
                    onFindOldFiles = { viewModel.findOldFiles(state) },
                    onReviewUncategorized = { viewModel.findUncategorized(state) },
                    onProtectFolders = { viewModel.reviewFolderProtection(state) },
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
                is ScanUiState.Working -> WorkingContent(state)
                is ScanUiState.Undoing -> UndoingContent(state)
                is ScanUiState.UndoDone -> UndoDoneContent(state, onDone = viewModel::reset)
                is ScanUiState.DuplicateReview -> DuplicateReviewContent(
                    state = state,
                    onTrashDuplicates = { viewModel.proposeTrashDuplicates(state) },
                    onBack = viewModel::reset,
                )
                is ScanUiState.FileListReview -> FileListReviewContent(state, onBack = viewModel::reset)
                is ScanUiState.ProtectFolders -> ProtectFoldersContent(
                    state = state,
                    onProtect = { folder -> viewModel.proposeProtectFolder(state, folder) },
                    onBack = viewModel::reset,
                )
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
private fun ScanSummaryContent(
    state: ScanUiState.Summary,
    onScanAgain: () -> Unit,
    onOrganizeApks: () -> Unit,
    onSmartCleanup: (Boolean) -> Unit,
    onFindDuplicates: () -> Unit,
    onFindLargestFiles: () -> Unit,
    onFindOldFiles: () -> Unit,
    onReviewUncategorized: () -> Unit,
    onProtectFolders: () -> Unit,
) {
    // One fence for one limitation. SAF mode can scan and browse; it can't
    // mutate or read contents, because those gateway methods are deliberately
    // unimplemented. Rather than offering actions that then fail three
    // different ways, the actions that need those capabilities aren't shown.
    val canMutate = state.mode == StorageAccessMode.DIRECT
    // Milestone 6 spec 6a: off by default, and deliberately re-defaulted to
    // off every time this screen is composed. A depth guard that silently
    // remembers "on" from a previous run is not a guard.
    var includeSubfolders by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = state.scopeLabel)
        Text(text = "${state.totalFiles} files · ${formatBytes(state.totalBytes)}")

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

            if (!canMutate) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text(
                            text = "Folder-only access: scanning and browsing work here. Moving, trashing, and " +
                                "duplicate detection need full file-manager access — change it in Settings.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            if (canMutate) {
                item {
                    // Plan Section 4/9: the rule engine's own generated plan —
                    // extension + project-keyword rules, no model. Distinct from
                    // the hard-coded APK-only test button below it.
                    Card(
                        onClick = { onSmartCleanup(includeSubfolders) },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "Smart cleanup (rule-based, no AI)")
                            Text(
                                text = if (includeSubfolders) {
                                    "Will also sort files already inside folders"
                                } else {
                                    "Only files sitting loose in ${state.scopeLabel}"
                                },
                            )
                        }
                    }
                }

                item {
                    // The opt-in itself, outside the tile so tapping it can't
                    // start a run. Spec 6a: sorting into existing folders is a
                    // per-run choice someone has to make on purpose.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "Include files inside subfolders")
                        Switch(
                            checked = includeSubfolders,
                            onCheckedChange = { includeSubfolders = it },
                        )
                    }
                }

                val apkCount = state.byCategory[FileCategory.APK]?.fileCount ?: 0
                if (apkCount > 0) {
                    item {
                        // Milestone 2's own exercise of create+move+validator+
                        // executor+journal — a hard-coded plan, kept as-is since
                        // it's still a useful minimal-case sanity check distinct
                        // from the general rule engine above.
                        Card(onClick = onOrganizeApks, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(text = "Test: move $apkCount APK(s) into an APKs subfolder", modifier = Modifier.padding(16.dp))
                        }
                    }
                }

                item {
                    Card(onClick = onFindDuplicates, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(text = "Find duplicates", modifier = Modifier.padding(16.dp))
                    }
                }
            }

            item {
                Card(onClick = onFindLargestFiles, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(text = "Find largest files", modifier = Modifier.padding(16.dp))
                }
            }
            item {
                Card(onClick = onFindOldFiles, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(text = "Find files older than 6 months", modifier = Modifier.padding(16.dp))
                }
            }
            item {
                Card(onClick = onReviewUncategorized, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(text = "Review uncategorized", modifier = Modifier.padding(16.dp))
                }
            }

            if (canMutate) {
                item {
                    Card(onClick = onProtectFolders, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "Protect folders from sorting")
                            Text(text = "Puts a $DO_NOT_SORT_MARKER file in a folder. Survives reinstall.")
                        }
                    }
                }
            }
        }

        Card(onClick = onScanAgain, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Scan again", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun ProtectFoldersContent(
    state: ScanUiState.ProtectFolders,
    onProtect: (ProtectableFolder) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = "Folders under ${state.scopeLabel}")
        Text(
            text = "A protected folder and everything inside it is off limits to Smart cleanup, " +
                "whatever the subfolder setting says. Remove the marker file to undo that.",
        )

        if (state.folders.isEmpty()) {
            Text(text = "No subfolders here.", modifier = Modifier.padding(top = 16.dp))
            Card(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = "Back", modifier = Modifier.padding(16.dp))
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.folders) { folder ->
                // A protected folder is not tappable: removing protection
                // means deleting a file, and nothing in this app deletes.
                // The marker is removed by hand, in a file manager, which is
                // the same rule Trash follows.
                if (folder.isProtected) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = folder.displayName)
                            Text(text = "Protected · ${folder.fileCount} file(s)")
                        }
                    }
                } else {
                    Card(onClick = { onProtect(folder) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = folder.displayName)
                            Text(text = "${folder.fileCount} file(s) · tap to protect")
                        }
                    }
                }
            }
        }

        Card(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Back", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun DuplicateReviewContent(state: ScanUiState.DuplicateReview, onTrashDuplicates: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = "${state.groups.size} duplicate set(s) found under ${state.scopeLabel}")
        Text(text = "Exact match only (SHA-256) — never inferred from name or size alone.")
        if (state.groups.isNotEmpty()) {
            // Spec item 4: the assertion that matters, stated before
            // approval. A preview of 3,316 rows is not reviewable; one line
            // saying every group keeps exactly one copy is.
            val kept = state.groups.size
            val wouldTrash = state.groups.sumOf { it.extras.size }
            Text(
                text = "$kept group(s) · $kept copy(ies) kept · $wouldTrash copy(ies) would move to Trash",
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (state.groups.isEmpty()) {
            Card(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = "Back", modifier = Modifier.padding(16.dp))
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.groups) { group -> DuplicateGroupCard(group) }
        }

        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(onClick = onTrashDuplicates) {
                Text(text = "Propose trashing extra copies", modifier = Modifier.padding(16.dp))
            }
            Card(onClick = onBack) { Text(text = "Back", modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "${group.members.size} identical copies")
            // Which copy survives is decided by KeeperSelector, not by scan
            // order, and it's labelled here so the choice is visible before
            // anything is proposed rather than discovered afterwards.
            Text(text = "Keeping: ${group.keeper.displayName}", modifier = Modifier.padding(top = 4.dp))
            Text(text = group.keeper.stableRef)
            if (group.extras.isNotEmpty()) {
                Text(text = "Would trash:", modifier = Modifier.padding(top = 4.dp))
                group.extras.forEach { extra ->
                    Text(text = "${extra.stableRef} (${formatBytes(extra.sizeBytes)})")
                }
            }
        }
    }
}

@Composable
private fun FileListReviewContent(state: ScanUiState.FileListReview, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = state.title)
        Text(text = "${state.records.size} file(s) — browse only, nothing planned yet")

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.records) { record ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = record.displayName)
                    Text(text = formatBytes(record.sizeBytes))
                }
            }
        }

        Card(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Back", modifier = Modifier.padding(16.dp))
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

        // Spec 6b: a protection that applies silently is indistinguishable
        // from a bug. Whatever the plan source declined to touch gets stated
        // here, above the row list, because a 3,000-row list is not read.
        if (state.scopeNotes.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Left out of this plan")
                    for (note in state.scopeNotes) {
                        Text(text = note)
                    }
                }
            }
        }

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
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = when {
                summary.failed == 0 -> "Task complete"
                summary.succeededTotal > 0 -> "Partly done"
                else -> "Task failed"
            },
        )
        if (summary.foldersCreated > 0) Text(text = "${summary.foldersCreated} folder(s) created")
        if (summary.filesMoved > 0) Text(text = "${summary.filesMoved} moved")
        if (summary.filesRenamed > 0) Text(text = "${summary.filesRenamed} renamed")
        if (summary.filesTrashed > 0) Text(text = "${summary.filesTrashed} trashed")
        if (summary.filesWritten > 0) Text(text = "${summary.filesWritten} file(s) written")
        if (summary.failed > 0) Text(text = "${summary.failed} failed")
        if (summary.leftUntouched.isNotEmpty()) Text(text = "${summary.leftUntouched.size} left untouched")

        // Spec item 2. The count alone was in the journal and nowhere on
        // screen: "1 failed" on a 4,835-operation run named neither the file
        // nor the reason. Same shape as "Left untouched" below it.
        if (summary.failures.isNotEmpty() || summary.leftUntouched.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (summary.failures.isNotEmpty()) {
                    item { Text(text = "Failed") }
                    items(summary.failures) { failure ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text = "${failure.operationType.name.lowercase().replace('_', ' ')}: ${failure.subject}")
                                Text(text = failure.reason)
                            }
                        }
                    }
                }
                if (summary.leftUntouched.isNotEmpty()) {
                    item { Text(text = "Left untouched", modifier = Modifier.padding(top = 8.dp)) }
                    items(summary.leftUntouched) { rejected ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text = operationSummary(rejected.operation))
                                Text(text = rejected.reason)
                            }
                        }
                    }
                }
            }
        }

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

/**
 * Every long operation's feedback. Before this existed, tapping an action
 * left the previous screen on display for the whole operation — on a 13,000
 * file scope that is indistinguishable from a dead button.
 */
@Composable
private fun WorkingContent(state: ScanUiState.Working) {
    Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
        val processed = state.processed
        val total = state.total
        if (processed != null && total != null && total > 0) {
            LinearProgressIndicator(
                progress = { processed.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = "${state.label}: $processed of $total", modifier = Modifier.padding(top = 16.dp))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = state.label, modifier = Modifier.padding(top = 16.dp))
        }
        state.detail?.let { Text(text = it, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun UndoingContent(state: ScanUiState.Undoing) {
    Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { state.completed.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = "Restoring ${state.completed} of ${state.total}…", modifier = Modifier.padding(top = 16.dp))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = "Reading the journal…", modifier = Modifier.padding(top = 16.dp))
        }
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
    is PlannedOperation.WriteTextFile -> "Write ${operation.name} into ${operation.parent.shortPath()}"
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
