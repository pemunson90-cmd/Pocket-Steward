package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.ui.theme.Spacing

/**
 * Where a scan starts: pick a scope, or open the picker. Also where a scan in
 * progress is shown, because a scan has no result yet to navigate to.
 */
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    autoAction: PostScanAction?,
    onOpenPicker: () -> Unit,
    onBack: () -> Unit,
) {
    val accessState by viewModel.storageAccessState.collectAsState(initial = null)
    val scanning by viewModel.scanning.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val recents by viewModel.recentFolders.collectAsState()

    LaunchedEffect(autoAction, accessState) {
        if (autoAction != null && accessState?.mode != null) {
            viewModel.startScanThen(ScanTarget.Downloads, autoAction)
        }
    }

    val targets = when (accessState?.mode) {
        StorageAccessMode.DIRECT -> listOf(
            ScanTarget.Downloads, ScanTarget.Documents, ScanTarget.Pictures, ScanTarget.Everything,
        )
        StorageAccessMode.SAF -> listOf(ScanTarget.GrantedFolder("Granted folder"))
        null -> emptyList()
    }
    val canBrowse = accessState?.mode == StorageAccessMode.DIRECT

    ScanFlowScaffold(
        title = "Scan storage",
        onBack = onBack,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
    ) { contentModifier ->
        val progress = scanning
        if (progress != null) {
            Column(modifier = contentModifier.fillMaxWidth()) {
                ScreenHeadline("Scanning", "${progress.processedCount} items so far")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                progress.currentDirectoryName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.tight),
                    )
                }
            }
            return@ScanFlowScaffold
        }

        if (targets.isEmpty()) {
            EmptyState("No storage access granted yet. Set it up in Settings.", contentModifier)
            return@ScanFlowScaffold
        }

        LazyColumn(
            modifier = contentModifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            if (canBrowse) {
                item {
                    // Spec 6c, kept above the category shortcuts: narrowing a
                    // run to one folder is the safest way to use this app, and
                    // burying it under four whole-category tiles says the
                    // opposite.
                    ScopeCard(
                        title = "Choose a folder…",
                        supporting = "Scan and act on one folder instead of a whole category",
                        onClick = onOpenPicker,
                    )
                }
            }

            if (recents.isNotEmpty()) {
                item { SectionHeader("Recent folders") }
                items(recents) { path ->
                    ScopeCard(
                        title = path.substringAfterLast('/').ifBlank { path },
                        supporting = path,
                        onClick = { viewModel.startScan(ScanTarget.CustomFolder(path)) },
                    )
                }
                item { SectionHeader("Everywhere else") }
            }

            items(targets) { target ->
                ScopeCard(title = target.label, supporting = null, onClick = { viewModel.startScan(target) })
            }
        }
    }
}

@Composable
private fun ScopeCard(title: String, supporting: String?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.screen)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
            }
        }
    }
}
