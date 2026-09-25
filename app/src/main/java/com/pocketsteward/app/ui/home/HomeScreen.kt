package com.pocketsteward.app.ui.home

import com.pocketsteward.app.ui.components.SmoothProgressBar
import java.io.File

import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.ui.scan.PostScanAction

@Composable
fun HomeScreen(
    onExplore: () -> Unit,
    onContinueLastScan: () -> Unit,
    onOpenTasks: () -> Unit,
    onAsk: () -> Unit,
    onNaturalLanguageRequest: (String) -> Unit,
    onQuickAction: (PostScanAction) -> Unit,
    onScheduledReview: () -> Unit,
    onSavedWorkflow: (String) -> Unit,
    onSavedSearch: (String) -> Unit,
    onImportedPlan: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    taskRunDao = container.database.taskRunDao(),
                    mutationRecordDao = container.database.mutationRecordDao(),
                    settingsRepository = container.settingsRepository,
                )
            }
        },
    )
    val recentTasks by viewModel.recentTasks.collectAsState()
    val taskProgress by viewModel.taskProgress.collectAsState()
    val savedWorkflows by viewModel.savedWorkflows.collectAsState()
    val savedSearches by viewModel.savedSearches.collectAsState()
    val lastScanSession by viewModel.lastScanSession.collectAsState()
    val pendingCleanupSuggestion by viewModel.pendingCleanupSuggestion.collectAsState()
    var prompt by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }

    val importPlanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val cacheFile = File(context.cacheDir, "reviewed-plan-import-${System.currentTimeMillis()}.json")
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open the selected file." }
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                onImportedPlan(cacheFile.absolutePath)
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Could not import the reviewed plan.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Pocket Steward",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Ask first. Nothing changes until you review it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val activeTask = recentTasks.firstOrNull {
            it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.CANCELLED
        }
        if (activeTask != null) {
            val total = DurablePlanCodec.decodeOrNull(activeTask.planJson)?.operations?.size ?: 0
            val journal = taskProgress[activeTask.id]
            val completed = if (total > 0 && journal != null) {
                journal.journaledCount.coerceAtMost(total.toLong()).toInt()
            } else {
                0
            }

            Card(onClick = onOpenTasks, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (activeTask.status == TaskRunStatus.RUNNING) "Task running" else "Task paused",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        activeTask.requestText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (total > 0) {
                        SmoothProgressBar(
                            fraction = completed.toFloat() / total.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "$completed of $total operations recorded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Open Tasks to review, pause, resume, inspect the manifest, or undo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        pendingCleanupSuggestion?.let { suggestion ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Scheduled review ready", style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(suggestion.newFileCount)
                            append(" new file")
                            if (suggestion.newFileCount != 1) append("s")
                            if (suggestion.obviousMatchCount > 0) {
                                append(" · ")
                                append(suggestion.obviousMatchCount)
                                append(" obvious organization match")
                                if (suggestion.obviousMatchCount != 1) append("es")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Nothing has moved. Open the fresh review to build and approve a plan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = onScheduledReview) {
                            Text("Review")
                        }
                        TextButton(onClick = viewModel::dismissCleanupSuggestion) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        lastScanSession?.let { session ->
            Card(onClick = onContinueLastScan, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Continue last scan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        session.roots.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Reopens the durable Room inventory without walking storage again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("Quick actions", style = MaterialTheme.typography.titleLarge)
        HomeQuickAction(
            title = "Ask your files",
            supporting = "Ask a question about what's inside your indexed documents. Answers cite their sources.",
            onClick = onAsk,
        )
        HomeQuickAction(
            title = "File inboxes into projects",
            supporting = "Treat Downloads (and any configured inboxes) as landing zones. Match APKs, source bundles, notes, and related files to project homes and releases.",
            onClick = { onQuickAction(PostScanAction.INBOX_FILING) },
        )
        HomeQuickAction(
            title = "Tidy Downloads locally",
            supporting = "Keep everything inside Downloads and organize only the loose files there.",
            onClick = { onQuickAction(PostScanAction.SMART_CLEANUP) },
        )
        HomeQuickAction(
            title = "Find duplicates",
            supporting = "Scan Downloads for exact SHA-256 duplicate sets.",
            onClick = { onQuickAction(PostScanAction.FIND_DUPLICATES) },
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < 480.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeQuickAction(
                        title = "Largest files",
                        supporting = "Top 50 in Downloads.",
                        onClick = { onQuickAction(PostScanAction.FIND_LARGEST) },
                    )
                    HomeQuickAction(
                        title = "Old files",
                        supporting = "Older than six months.",
                        onClick = { onQuickAction(PostScanAction.FIND_OLD) },
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HomeQuickAction(
                        title = "Largest files",
                        supporting = "Top 50 in Downloads.",
                        onClick = { onQuickAction(PostScanAction.FIND_LARGEST) },
                        modifier = Modifier.weight(1f),
                    )
                    HomeQuickAction(
                        title = "Old files",
                        supporting = "Older than six months.",
                        onClick = { onQuickAction(PostScanAction.FIND_OLD) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        HomeQuickAction(
            title = "Review uncategorized",
            supporting = "See files the deterministic rules deliberately left uncertain.",
            onClick = { onQuickAction(PostScanAction.REVIEW_UNCATEGORIZED) },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Ask Pocket Steward", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text("Organize the obvious files and leave uncertain things alone") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Requests run locally. File changes still go through preview and approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onNaturalLanguageRequest(prompt) },
                    enabled = prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ask")
                }
            }
        }

        if (savedWorkflows.isNotEmpty()) {
            Text("Saved", style = MaterialTheme.typography.titleLarge)
            savedWorkflows.forEach { workflow ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            workflow.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(workflow.roots.size)
                                append(if (workflow.roots.size == 1) " folder" else " folders")
                                if (workflow.request.isNotBlank()) {
                                    append(" · ")
                                    append(workflow.request)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Button(onClick = { onSavedWorkflow(workflow.id) }) {
                                Text("Run")
                            }
                            TextButton(onClick = { viewModel.deleteSavedWorkflow(workflow.id) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }

        if (savedSearches.isNotEmpty()) {
            Text("Saved searches", style = MaterialTheme.typography.titleLarge)
            savedSearches.forEach { saved ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            saved.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append("“")
                                append(saved.query)
                                append("” · ")
                                append(saved.roots.size)
                                append(if (saved.roots.size == 1) " folder" else " folders")
                                append(" · ")
                                append(saved.lastResultCount)
                                append(" last result")
                                if (saved.lastResultCount != 1) append("s")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Button(onClick = { onSavedSearch(saved.id) }) {
                                Text("Open")
                            }
                            TextButton(onClick = { viewModel.deleteSavedSearch(saved.id) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }

        Card(
            onClick = {
                importPlanLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Import reviewed plan", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Open a Pocket Steward reviewed-plan JSON. It will be rescanned and revalidated before anything can run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Card(onClick = onExplore, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Explore", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Choose folders, scan storage, search contents, find duplicates, and organize files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Card(onClick = onOpenTasks, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Tasks", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (recentTasks.isEmpty()) {
                        "No task history yet"
                    } else {
                        "${recentTasks.size} recent task${if (recentTasks.size == 1) "" else "s"}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}


@Composable
private fun HomeQuickAction(
    title: String,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}