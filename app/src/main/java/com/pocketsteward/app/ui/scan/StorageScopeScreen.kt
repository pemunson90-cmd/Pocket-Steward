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
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.StorageAccessMode

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
                is ScanUiState.Summary -> ScanSummaryContent(state, onScanAgain = viewModel::reset)
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
private fun ScanSummaryContent(state: ScanUiState.Summary, onScanAgain: () -> Unit) {
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

        Card(onClick = onScanAgain, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Scan again", modifier = Modifier.padding(16.dp))
        }
    }
}

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
