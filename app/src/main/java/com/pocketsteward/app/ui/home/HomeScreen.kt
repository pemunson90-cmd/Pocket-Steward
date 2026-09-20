package com.pocketsteward.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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

@Composable
fun HomeScreen(
    onExplore: () -> Unit,
    onOpenTasks: () -> Unit,
    onNaturalLanguageRequest: (String) -> Unit,
    onSavedWorkflow: (String) -> Unit,
    onSavedSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    taskRunDao = container.database.taskRunDao(),
                    settingsRepository = container.settingsRepository,
                )
            }
        },
    )
    val recentTasks by viewModel.recentTasks.collectAsState()
    val savedWorkflows by viewModel.savedWorkflows.collectAsState()
    val savedSearches by viewModel.savedSearches.collectAsState()
    var prompt by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }

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
