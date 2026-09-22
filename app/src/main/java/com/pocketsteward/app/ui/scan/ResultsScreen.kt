package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.ui.theme.Spacing

@Composable
fun ResultsScreen(viewModel: ScanViewModel, onBack: () -> Unit, onScanAgain: () -> Unit = onBack) {
    val summary by viewModel.summary.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var includeSubfolders by remember { mutableStateOf(false) }
    var request by rememberSaveable { mutableStateOf("") }
    var workflowName by rememberSaveable { mutableStateOf("") }

    val state = summary
    ScanFlowScaffold(
        title = "Explore",
        onBack = onBack,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
    ) { contentModifier ->
        if (state == null) {
            EmptyState("Nothing scanned yet.", contentModifier)
            return@ScanFlowScaffold
        }

        val canChangeFiles = state.mode == StorageAccessMode.DIRECT

        Column(modifier = contentModifier.fillMaxWidth()) {
            ScreenHeadline(
                text = state.scopeLabel,
                supporting = "${state.totalFiles} files · ${formatBytes(state.totalBytes)}",
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                item { SectionHeader("Overview") }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Spacing.base)) {
                            FileCategory.entries.forEach { category ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.hairline),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(category.friendlyName(), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${state.byCategory[category]?.fileCount ?: 0}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Files ≥ 500 MB", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${state.largeFileCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = Spacing.hairline),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Uncategorized", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${state.uncategorizedCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (!canChangeFiles) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "This access mode allows browsing and read-only analysis. Change storage access in Settings to organize, move, trash, or export files.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(Spacing.base),
                            )
                        }
                    }
                }

                item { SectionHeader("Ask about this scan") }
                item {
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        placeholder = { Text("Find documents containing Lilith") },
                        supportingText = {
                            Text("Requests apply only to the folders in this scan.")
                        },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(
                        onClick = { viewModel.handleNaturalLanguage(state, request) },
                        enabled = request.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ask")
                    }
                }


                if (canChangeFiles) {
                    item { SectionHeader("Save this setup") }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(Spacing.base)) {
                                Text(
                                    "Save these folders${if (request.isNotBlank()) " + current Ask text" else ""}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Running it later performs a fresh scan before rerunning the request.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = Spacing.hairline),
                                )
                                OutlinedTextField(
                                    value = workflowName,
                                    onValueChange = { workflowName = it },
                                    placeholder = { Text("Writing cleanup") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                                )
                                Button(
                                    onClick = {
                                        viewModel.saveWorkflow(state, workflowName, request)
                                        workflowName = ""
                                    },
                                    enabled = workflowName.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                                ) {
                                    Text("Save workflow")
                                }
                            }
                        }
                    }
                }

                if (canChangeFiles) {
                    item { SectionHeader("Organize") }
                    item {
                        ActionCard(
                            title = "Smart cleanup",
                            supporting = if (includeSubfolders) {
                                "Organize confident matches, including files already inside folders."
                            } else {
                                "Organize confident matches sitting directly in the selected folders."
                            },
                            onClick = { viewModel.proposeSmartCleanup(state, includeSubfolders) },
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.hairline),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = Spacing.base)) {
                                Text("Include nested files", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Off by default so existing folder structures stay untouched.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = includeSubfolders, onCheckedChange = { includeSubfolders = it })
                        }
                    }
                }

                item { SectionHeader("Find") }
                item {
                    ActionCard(
                        title = "Duplicates",
                        supporting = if (canChangeFiles) {
                            "Find byte-identical copies and choose what to keep."
                        } else {
                            "Find byte-identical copies by SHA-256. Review is read-only in this access mode."
                        },
                        onClick = { viewModel.findDuplicates(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Largest files",
                        supporting = "See the 50 files using the most space.",
                        onClick = { viewModel.findLargestFiles(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Older files",
                        supporting = "See files not modified in the last six months.",
                        onClick = { viewModel.findOldFiles(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Uncategorized",
                        supporting = "See what the deterministic rules could not place confidently.",
                        onClick = { viewModel.findUncategorized(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Similar files",
                        supporting = "Find visually similar images and near-duplicate indexed documents. Review only; similarity never authorizes trash.",
                        onClick = { viewModel.findSimilarFiles(state) },
                    )
                }

                item { SectionHeader("Export") }
                item {
                    ActionCard(
                        title = "Export inventory",
                        supporting = "Write verified JSON and CSV inventories into each selected scan root.",
                        onClick = { viewModel.exportInventory(state) },
                    )
                }

                item { SectionHeader("Understand") }
                item {
                    ActionCard(
                        title = "Rich metadata",
                        supporting = "Read image dimensions, media duration, APK metadata, PDF page counts, EXIF, and ZIP listings locally.",
                        onClick = { viewModel.enrichMetadata(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Image understanding",
                        supporting = "Label a bounded set of recent images locally and identify likely screenshots. Nothing leaves the device.",
                        onClick = { viewModel.analyzeImages(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Document audit",
                        supporting = if (canChangeFiles) {
                            "Analyze a representative document sample with local extraction and on-device intelligence, then optionally build a reviewed organization proposal."
                        } else {
                            "Analyze a representative document sample locally. Findings are read-only in selected-folder mode."
                        },
                        onClick = { viewModel.runCoherenceAudit(state) },
                    )
                }

                if (canChangeFiles) {
                    item { SectionHeader("Protect") }
                    item {
                        ActionCard(
                            title = "Protect folders",
                            supporting = "Keep selected folders and everything inside them out of automated organization.",
                            onClick = { viewModel.reviewFolderProtection(state) },
                        )
                    }
                }

                item {
                    OutlinedButton(
                        onClick = onScanAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.base, bottom = Spacing.section),
                    ) {
                        Text("Choose different folders")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, supporting: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.screen)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
        }
    }
}

private fun FileCategory.friendlyName(): String = when (this) {
    FileCategory.IMAGE -> "Images"
    FileCategory.DOCUMENT -> "Documents"
    FileCategory.APK -> "App installers"
    FileCategory.ARCHIVE -> "Archives"
    FileCategory.AUDIO_VIDEO -> "Audio & video"
    FileCategory.OTHER -> "Other"
}
