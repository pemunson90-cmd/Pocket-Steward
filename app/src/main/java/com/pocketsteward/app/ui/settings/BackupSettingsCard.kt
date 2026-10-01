package com.pocketsteward.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackupSettingsCard(model: BackupViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::inspect) }
    var showHistory by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup and restore", style = MaterialTheme.typography.titleMedium)
            Text("Save project homes, templates, workflows, preferences and task summaries. Includes file paths and request text; excludes file contents, signing keys, storage grants and execution journals.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { export.launch("PocketSteward-settings-backup.json") }, enabled = !state.busy) { Text("Export verified backup") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = !state.busy) { Text("Choose backup to restore") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.pending?.let { backup ->
                Text("Backup from ${DateFormat.getDateTimeInstance().format(Date(backup.createdAt))}")
                Text("Replace saved settings with ${backup.settings.size} entries and import ${backup.history.size} read-only task summaries. Background reviews stay off. Existing task history and undo journals remain in place.")
                Button(onClick = model::restore, enabled = !state.busy) { Text("Restore these settings") }
                TextButton(onClick = model::dismissImport, enabled = !state.busy) { Text("Cancel restore") }
            }
            if (state.importedHistory.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }) { Text("Imported history · ${state.importedHistory.size} summaries") }
                if (showHistory) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.importedHistory) { _, task ->
                        Column {
                            Text(task.request, style = MaterialTheme.typography.titleSmall)
                            Text("${task.status} · ${DateFormat.getDateTimeInstance().format(Date(task.startedAt))}", style = MaterialTheme.typography.labelSmall)
                            task.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("Read-only imported summary", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
