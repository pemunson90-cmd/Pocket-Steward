package com.pocketsteward.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: HistoryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HistoryViewModel(container.database.taskRunDao(), container.settingsRepository, container)
            }
        },
    )

    val taskRuns by viewModel.taskRuns.collectAsState()
    val undoState by viewModel.undoState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (taskRuns.isEmpty()) {
                Text("No organize tasks yet.")
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(taskRuns, key = { it.id }) { taskRun ->
                    TaskRunRow(
                        taskRun = taskRun,
                        undoState = undoState,
                        onUndo = { viewModel.undo(taskRun.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRunRow(taskRun: TaskRun, undoState: UndoUiState, onUndo: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = taskRun.requestText)
            Text(text = taskRun.status.name)
            taskRun.summary?.let { Text(text = it) }

            when {
                undoState is UndoUiState.InProgress && undoState.taskRunId == taskRun.id -> {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
                undoState is UndoUiState.Done && undoState.summary.taskRunId == taskRun.id -> {
                    val summary = undoState.summary
                    Text(
                        text = if (summary.blockedAt == null) {
                            "Undone: ${summary.undone} operation(s)"
                        } else {
                            "Undo stopped after ${summary.undone}: ${summary.blockedAt.reason}"
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                canUndo(taskRun) -> {
                    Card(onClick = onUndo, modifier = Modifier.padding(top = 8.dp)) {
                        Text(text = "Undo", modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

private fun canUndo(taskRun: TaskRun): Boolean =
    taskRun.status == TaskRunStatus.COMPLETED || taskRun.status == TaskRunStatus.FAILED
