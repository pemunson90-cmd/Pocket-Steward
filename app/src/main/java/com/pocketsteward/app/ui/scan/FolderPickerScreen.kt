package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.pocketsteward.app.picker.FolderFilters
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.picker.FolderPicker
import com.pocketsteward.app.picker.FolderSort
import com.pocketsteward.app.ui.theme.Spacing

/**
 * Spec 2. The picker used to list a directory's subfolders in whatever order
 * the filesystem returned them, with no way to narrow the list — unusable on a
 * real Downloads folder.
 *
 * The search, sort and filter decisions live in
 * [com.pocketsteward.app.picker.FolderPicker], which is pure Kotlin and
 * tested. This file only draws the controls and reports what they are set to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(viewModel: ScanViewModel, onBack: () -> Unit) {
    val state by viewModel.picker.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val recents by viewModel.recentFolders.collectAsState()

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(FolderSort.NAME) }
    var filters by remember { mutableStateOf(FolderFilters()) }

    val browser = state
    ScanFlowScaffold(
        title = browser?.currentDisplayName ?: "Choose a folder",
        onBack = onBack,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
    ) { contentModifier ->
        if (browser == null) {
            EmptyState("Opening…", contentModifier)
            return@ScanFlowScaffold
        }

        // FolderPicker decides what is shown and in what order; this only
        // maps its answer back to the typed refs the rest of the flow needs.
        val byPath = browser.children.associateBy { it.folder.path }
        val rows = FolderPicker.apply(
            folders = browser.children.map { it.folder },
            query = query,
            sort = sort,
            filters = filters,
        ).mapNotNull { byPath[it.path] }

        Column(modifier = contentModifier.fillMaxWidth()) {
            Text(
                text = browser.current.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (browser.currentIsProtected) {
                Text(
                    text = "This folder is protected from sorting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search folders") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
            )

            // One control for sort rather than four toggles, per spec 2b.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                FolderSort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = { Text(option.label) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.hairline),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                FilterChip(
                    selected = filters.hideEmpty,
                    onClick = { filters = filters.copy(hideEmpty = !filters.hideEmpty) },
                    label = { Text("Hide empty") },
                )
                FilterChip(
                    selected = filters.onlyProtected,
                    onClick = { filters = filters.copy(onlyProtected = !filters.onlyProtected) },
                    label = { Text("Protected only") },
                )
                FilterChip(
                    selected = filters.hideSystem,
                    onClick = { filters = filters.copy(hideSystem = !filters.hideSystem) },
                    label = { Text("Hide system") },
                )
            }

            Text(
                text = "${rows.size} of ${browser.children.size} subfolder(s) · counts are direct contents only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.tight),
            )

            LazyColumn(
                modifier = Modifier.weight(1f).padding(top = Spacing.tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                if (recents.isNotEmpty() && query.isBlank()) {
                    item { SectionHeader("Recent") }
                    items(recents) { path ->
                        Card(
                            onClick = { viewModel.browseFolders(FileRef.Direct(path)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(Spacing.base)) {
                                Text(
                                    text = path.substringAfterLast('/').ifBlank { path },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    item { SectionHeader("In this folder") }
                }

                browser.parent?.let { parent ->
                    item {
                        Card(onClick = { viewModel.browseFolders(parent) }, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(Spacing.base)) {
                                Text(text = "Up one level", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = parent.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (rows.isEmpty()) {
                    item {
                        EmptyState(
                            if (browser.children.isEmpty()) {
                                "No subfolders here. You can still scan this folder."
                            } else {
                                "Nothing matches. Clear the search or the filters."
                            },
                        )
                    }
                }

                items(rows, key = { it.folder.path }) { row ->
                    Card(onClick = { viewModel.browseFolders(row.ref) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Spacing.base)) {
                            Text(text = row.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = buildString {
                                    append("${row.folder.fileCount} file(s)")
                                    if (row.folder.totalBytes > 0) append(" · ${formatBytes(row.folder.totalBytes)}")
                                    if (row.isProtected) append(" · protected")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { viewModel.proposeToggleProtection(browser, row) },
                                modifier = Modifier.padding(top = Spacing.hairline),
                            ) {
                                Text(if (row.isProtected) "Remove protection" else "Protect this folder")
                            }
                        }
                    }
                }
            }

            ActionRow {
                Button(onClick = { viewModel.scanBrowsedFolder(browser.current) }) {
                    Text("Scan this folder")
                }
                OutlinedButton(onClick = { viewModel.proposeToggleProtectionHere(browser) }) {
                    Text(if (browser.currentIsProtected) "Unprotect" else "Protect")
                }
            }
        }
    }
}
