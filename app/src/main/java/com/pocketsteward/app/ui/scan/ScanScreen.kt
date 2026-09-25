package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
    autoRequest: String?,
    autoWorkflowId: String?,
    autoSavedSearchId: String?,
    autoImportedPlanPath: String?,
    autoScheduledReview: Boolean,
    autoRestoreLast: Boolean,
    onOpenPicker: () -> Unit,
    onBack: () -> Unit,
) {
    val accessState by viewModel.storageAccessState.collectAsState(initial = null)
    val scanning by viewModel.scanning.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val recents by viewModel.recentFolders.collectAsState()
    val selectedTargets by viewModel.selectedTargets.collectAsState()

    LaunchedEffect(
        autoAction,
        autoRequest,
        autoWorkflowId,
        autoSavedSearchId,
        autoImportedPlanPath,
        autoScheduledReview,
        autoRestoreLast,
        accessState,
    ) {
        if (accessState?.mode != null) {
            when {
                autoRestoreLast -> viewModel.startLastScanSession()
                autoScheduledReview -> viewModel.startScheduledSuggestion()
                !autoImportedPlanPath.isNullOrBlank() -> viewModel.startImportedReviewedPlan(autoImportedPlanPath)
                !autoSavedSearchId.isNullOrBlank() -> viewModel.startSavedSearch(autoSavedSearchId)
                !autoWorkflowId.isNullOrBlank() -> viewModel.startSavedWorkflow(autoWorkflowId)
                !autoRequest.isNullOrBlank() -> viewModel.startScanThenRequest(ScanTarget.Downloads, autoRequest)
                autoAction == PostScanAction.INBOX_FILING -> viewModel.startConfiguredInboxFiling()
                autoAction != null -> viewModel.startScanThen(ScanTarget.Downloads, autoAction)
            }
        }
    }

    val targets = when (accessState?.mode) {
        StorageAccessMode.DIRECT -> listOf(
            ScanTarget.Downloads, ScanTarget.Documents, ScanTarget.Pictures, ScanTarget.Everything,
        )
        StorageAccessMode.SAF -> listOf(ScanTarget.GrantedFolder("Granted folder"))
        null -> emptyList()
    }
    val canBrowse = accessState?.mode != null

    ScanFlowScaffold(
        title = "Explore",
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
                Text(
                    text = "Pause keeps the current scan checkpoint so the same folders can resume later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.tight),
                )
                OutlinedButton(
                    onClick = viewModel::cancelScan,
                    modifier = Modifier.padding(top = Spacing.base),
                ) {
                    Text("Pause scan")
                }
            }
            return@ScanFlowScaffold
        }

        if (targets.isEmpty()) {
            EmptyState("No storage access granted yet. Set it up in Settings.", contentModifier)
            return@ScanFlowScaffold
        }

        Column(modifier = contentModifier.fillMaxWidth()) {
            ScreenHeadline(
                text = "Choose where to look",
                supporting = "Pick one or more folders. Pocket Steward keeps each root local unless you explicitly ask otherwise.",
            )

            if (selectedTargets.isNotEmpty()) {
                Text(
                    text = "Selected",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = Spacing.hairline),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.base),
                ) {
                    items(selectedTargets) { target ->
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.toggleScanTarget(target) },
                            label = { Text(target.label) },
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                if (canBrowse) {
                    item {
                        ScopeCard(
                            title = "Browse folders",
                            supporting = if (accessState?.mode == StorageAccessMode.SAF) {
                                "Choose a subfolder inside the granted Android document tree"
                            } else {
                                "Add any folder on your device"
                            },
                            selected = false,
                            showCheckbox = false,
                            onClick = onOpenPicker,
                        )
                    }
                }

                if (recents.isNotEmpty()) {
                    item { SectionHeader("Recent folders") }
                    items(recents) { path ->
                        val target: ScanTarget = if (path.startsWith("content://")) {
                            ScanTarget.GrantedSubfolder(
                                documentUri = path,
                                label = path.substringAfterLast('/').ifBlank { "Selected subfolder" },
                            )
                        } else {
                            ScanTarget.CustomFolder(path)
                        }
                        ScopeCard(
                            title = target.label,
                            supporting = path,
                            selected = selectedTargets.containsTarget(target),
                            onClick = { viewModel.toggleScanTarget(target) },
                        )
                    }
                    item { SectionHeader("Quick locations") }
                }

                items(targets) { target ->
                    ScopeCard(
                        title = target.label,
                        supporting = null,
                        selected = selectedTargets.containsTarget(target),
                        onClick = { viewModel.toggleScanTarget(target) },
                    )
                }
            }

            ActionRow {
                Button(
                    onClick = viewModel::startSelectedScan,
                    enabled = selectedTargets.isNotEmpty(),
                ) {
                    Text(
                        if (selectedTargets.size == 1) {
                            "Explore selected folder"
                        } else {
                            "Explore ${selectedTargets.size} selected folders"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeCard(
    title: String,
    supporting: String?,
    selected: Boolean,
    showCheckbox: Boolean = true,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Spacing.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCheckbox) {
                Checkbox(checked = selected, onCheckedChange = null)
            }
            Column(modifier = Modifier.weight(1f)) {
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
}

private fun List<ScanTarget>.containsTarget(target: ScanTarget): Boolean = any { candidate ->
    when {
        candidate is ScanTarget.CustomFolder && target is ScanTarget.CustomFolder ->
            candidate.absolutePath.trimEnd('/') == target.absolutePath.trimEnd('/')
        candidate is ScanTarget.GrantedFolder && target is ScanTarget.GrantedFolder ->
            candidate.label == target.label
        candidate is ScanTarget.GrantedSubfolder && target is ScanTarget.GrantedSubfolder ->
            candidate.documentUri == target.documentUri
        else -> candidate::class == target::class
    }
}