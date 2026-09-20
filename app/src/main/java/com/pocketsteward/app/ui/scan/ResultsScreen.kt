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
import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.ui.theme.Spacing

/**
 * The scan result and what can be done with it.
 *
 * This is the destination back returns to from every review, preview and
 * completion screen, which is the whole point of M7 item 1: the index behind
 * [ScanUiState.Summary] cost a full filesystem walk to build and must not be
 * thrown away by a back press.
 */
@Composable
fun ResultsScreen(viewModel: ScanViewModel, onBack: () -> Unit, onScanAgain: () -> Unit = onBack) {
    val summary by viewModel.summary.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()

    // Spec 6a: off by default, and re-defaulted to off on every composition.
    // A depth guard that silently remembers "on" from a previous run is not a
    // guard.
    var includeSubfolders by remember { mutableStateOf(false) }
    var request by rememberSaveable { mutableStateOf("") }

    val state = summary
    ScanFlowScaffold(
        title = state?.scopeLabel ?: "Results",
        onBack = onBack,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
    ) { contentModifier ->
        if (state == null) {
            EmptyState("Nothing scanned yet.", contentModifier)
            return@ScanFlowScaffold
        }

        // One fence for one limitation. SAF mode can scan and browse; it
        // can't mutate or read contents, because those gateway methods are
        // deliberately unimplemented. Rather than offering actions that then
        // fail three different ways, the actions that need those capabilities
        // aren't shown.
        val canMutate = state.mode == StorageAccessMode.DIRECT

        Column(modifier = contentModifier.fillMaxWidth()) {
            ScreenHeadline(
                text = state.scopeLabel,
                supporting = "${state.totalFiles} files · ${formatBytes(state.totalBytes)}",
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                item { SectionHeader("What's here") }
                items(FileCategory.entries) { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.hairline),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = category.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${state.byCategory[category]?.fileCount ?: 0}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!canMutate) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(top = Spacing.base)) {
                            Text(
                                text = "Folder-only access: scanning and browsing work here. Moving, trashing, " +
                                    "and duplicate detection need full file-manager access — change it in Settings.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(Spacing.base),
                            )
                        }
                    }
                }

                item { SectionHeader("Ask Pocket Steward") }
                item {
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        label = { Text("What should I do with these files?") },
                        supportingText = {
                            Text("Offline deterministic parser. Unknown requests are refused rather than guessed.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(
                        onClick = { viewModel.handleNaturalLanguage(state, request) },
                        enabled = request.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Build request")
                    }
                }

                item { SectionHeader("Actions") }

                if (canMutate) {
                    item {
                        ActionCard(
                            title = "Smart cleanup",
                            supporting = if (includeSubfolders) {
                                "Rule-based, no AI. Will also sort files already inside folders."
                            } else {
                                "Rule-based, no AI. Only files sitting loose in ${state.scopeLabel}."
                            },
                            onClick = { viewModel.proposeSmartCleanup(state, includeSubfolders) },
                        )
                    }
                    item {
                        // Outside the tile so tapping the switch can't start a
                        // run. Spec 6a: sorting into existing folders is a
                        // per-run choice someone has to make on purpose.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.hairline),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Include files inside subfolders", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = includeSubfolders, onCheckedChange = { includeSubfolders = it })
                        }
                    }

                    val apkCount = state.byCategory[FileCategory.APK]?.fileCount ?: 0
                    if (apkCount > 0) {
                        item {
                            ActionCard(
                                title = "Move $apkCount APK(s) into an APKs subfolder",
                                supporting = "Milestone 2's fixed-plan sanity check",
                                onClick = { viewModel.proposeOrganizeApks(state) },
                            )
                        }
                    }

                    item {
                        ActionCard(
                            title = "Find duplicates",
                            supporting = "Exact match only, by SHA-256",
                            onClick = { viewModel.findDuplicates(state) },
                        )
                    }
                    item {
                        ActionCard(
                            title = "Protect folders from sorting",
                            supporting = "Puts a $DO_NOT_SORT_MARKER file in a folder. Survives reinstall.",
                            onClick = { viewModel.reviewFolderProtection(state) },
                        )
                    }
                }

                item {
                    ActionCard(
                        title = "Find largest files",
                        supporting = "Browse only",
                        onClick = { viewModel.findLargestFiles(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Find files older than 6 months",
                        supporting = "Browse only",
                        onClick = { viewModel.findOldFiles(state) },
                    )
                }
                item {
                    ActionCard(
                        title = "Review uncategorized",
                        supporting = "What the rules could not place, and why",
                        onClick = { viewModel.findUncategorized(state) },
                    )
                }
            }

            ActionRow {
                // The one thing that genuinely throws the index away, and the
                // only caller of reset() left in the UI. Back does not do
                // this, which is the entire point of M7 item 1.
                OutlinedButton(onClick = onScanAgain) { Text("Scan again") }
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, supporting: String?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.screen)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
            }
        }
    }
}
