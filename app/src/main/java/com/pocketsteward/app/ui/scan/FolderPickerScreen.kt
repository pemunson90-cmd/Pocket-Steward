package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.pocketsteward.app.picker.FolderFilters
import com.pocketsteward.app.picker.FolderPicker
import com.pocketsteward.app.picker.FolderSort
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.ui.theme.Spacing

/**
 * Adaptive folder picker. Search/sort/filter controls are content-sized, and
 * the selection actions live inside the scrollable content rather than being
 * pinned below an otherwise empty weighted list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(viewModel: ScanViewModel, onBack: () -> Unit) {
    val state by viewModel.picker.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val recents by viewModel.recentFolders.collectAsState()
    val selectedTargets by viewModel.selectedTargets.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(FolderSort.NAME) }
    var hideEmpty by rememberSaveable { mutableStateOf(false) }
    var onlyProtected by rememberSaveable { mutableStateOf(false) }
    var hideSystem by rememberSaveable { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }

    val filters = FolderFilters(
        hideEmpty = hideEmpty,
        onlyProtected = onlyProtected,
        hideSystem = hideSystem,
    )

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

        val byPath = browser.children.associateBy { it.folder.path }
        val rows = FolderPicker.apply(
            folders = browser.children.map { it.folder },
            query = query,
            sort = sort,
            filters = filters,
        ).mapNotNull { byPath[it.path] }

        val currentTarget: ScanTarget? = when (val current = browser.current) {
            is FileRef.Direct -> ScanTarget.CustomFolder(current.absolutePath)
            is FileRef.Saf -> ScanTarget.GrantedSubfolder(
                documentUri = current.documentUri,
                label = browser.currentDisplayName,
            )
            is FileRef.Child -> null
        }
        val currentSelected = currentTarget != null && selectedTargets.any { selected ->
            when {
                selected is ScanTarget.CustomFolder && currentTarget is ScanTarget.CustomFolder ->
                    selected.absolutePath.trimEnd('/') == currentTarget.absolutePath.trimEnd('/')
                selected is ScanTarget.GrantedSubfolder && currentTarget is ScanTarget.GrantedSubfolder ->
                    selected.documentUri == currentTarget.documentUri
                else -> false
            }
        }
        val activeFilterCount = listOf(hideEmpty, onlyProtected, hideSystem).count { it }

        LazyColumn(
            modifier = contentModifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            item {
                Text(
                    text = browser.current.rawValue(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (browser.currentIsProtected) {
                item {
                    Text(
                        text = "This folder is protected from sorting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search folders") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { sortMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sort: ${sort.label}", maxLines = 1)
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            FolderSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = sort == option, onClick = null)
                                            Text(option.label)
                                        }
                                    },
                                    onClick = {
                                        sort = option
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { filtersOpen = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (activeFilterCount == 0) "Filters" else "Filters ($activeFilterCount)",
                            maxLines = 1,
                        )
                    }
                }
            }

            item {
                Text(
                    text = "${rows.size} of ${browser.children.size} subfolder(s) · direct contents",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (recents.isNotEmpty() && query.isBlank()) {
                item { SectionHeader("Recent") }
                items(recents) { path ->
                    Card(
                        onClick = { viewModel.browseFolders(parseFileRef(path)) },
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
                    Card(
                        onClick = {
                            viewModel.browseFolders(
                                startAt = parent,
                                ancestors = browser.ancestors.dropLast(1),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(Spacing.base)) {
                            Text("Up one level", style = MaterialTheme.typography.titleSmall)
                            Text(
                                parent.rawValue(),
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
                            "Nothing matches. Clear the search or filters."
                        },
                    )
                }
            }

            items(rows, key = { it.folder.path }) { row ->
                Card(
                    onClick = {
                        viewModel.browseFolders(
                            startAt = row.ref,
                            ancestors = browser.ancestors + browser.current,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(Spacing.base)) {
                        Text(
                            row.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("${row.folder.fileCount} file(s)")
                                if (row.folder.totalBytes > 0) append(" · ${formatBytes(row.folder.totalBytes)}")
                                if (row.isProtected) append(" · protected")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { viewModel.proposeToggleProtection(browser, row) },
                        ) {
                            Text(if (row.isProtected) "Remove protection" else "Protect this folder")
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.base, bottom = Spacing.section),
                    verticalArrangement = Arrangement.spacedBy(Spacing.tight),
                ) {
                    OutlinedButton(
                        onClick = { currentTarget?.let(viewModel::toggleScanTarget) },
                        enabled = currentTarget != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (currentSelected) "Remove this folder from selection" else "Add this folder")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Done")
                        }
                        OutlinedButton(
                            onClick = { viewModel.proposeToggleProtectionHere(browser) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (browser.currentIsProtected) "Unprotect" else "Protect")
                        }
                    }
                }
            }
        }
    }

    if (filtersOpen) {
        ModalBottomSheet(onDismissRequest = { filtersOpen = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                Text("Folder filters", style = MaterialTheme.typography.headlineSmall)
                FolderFilterRow("Hide empty folders", hideEmpty) { hideEmpty = !hideEmpty }
                FolderFilterRow("Protected folders only", onlyProtected) { onlyProtected = !onlyProtected }
                FolderFilterRow("Hide system folders", hideSystem) { hideSystem = !hideSystem }
                OutlinedButton(
                    onClick = { filtersOpen = false },
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.section),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun FolderFilterRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = Spacing.hairline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = Spacing.tight))
    }
}
