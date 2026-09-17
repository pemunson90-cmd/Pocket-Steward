package com.pocketsteward.app.ui.history

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunStatus
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: HistoryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HistoryViewModel(container.database.taskRunDao(), container.undoExecutor) }
        },
    )
    val tasks by viewModel.tasks.collectAsState()
    val actionState by viewModel.actionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (val action = actionState) {
                HistoryActionState.Idle -> Unit
                is HistoryActionState.Undoing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Undoing task ${action.taskRunId}…", modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                }
                is HistoryActionState.Done -> {
                    Card(onClick = viewModel::dismissAction, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text(
                            if (action.summary.complete) {
                                "Undo complete: ${action.summary.undone} restored. Tap to dismiss."
                            } else {
                                "Undo needs attention: ${action.summary.blocked} blocked. Tap to dismiss."
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                is HistoryActionState.Error -> {
                    Card(onClick = viewModel::dismissAction, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text("Undo failed: ${action.message}. Tap to dismiss.", modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (tasks.isEmpty()) {
                Text("No tasks yet.")
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(task = task, onUndo = { viewModel.undo(task.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskRun, onUndo: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(task.requestText)
            Text("${task.status.displayName()} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.startedAt))}")
            task.summary?.takeIf { it.isNotBlank() }?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }

            if (task.status == TaskRunStatus.COMPLETED || task.status == TaskRunStatus.FAILED) {
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(onClick = onUndo) {
                        Text("Undo", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
            if (task.status == TaskRunStatus.NEEDS_REVIEW || task.status == TaskRunStatus.UNDO_PARTIAL) {
                Text("Filesystem state needs review before Pocket Steward will make another guess.", modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun TaskRunStatus.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
