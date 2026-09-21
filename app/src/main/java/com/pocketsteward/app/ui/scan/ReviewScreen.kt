package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.pocketsteward.app.content.ContentMatch
import com.pocketsteward.app.content.index.ContentSearchFilters
import com.pocketsteward.app.content.index.ContentSearchPresentation
import com.pocketsteward.app.content.index.ContentSearchProvenance
import com.pocketsteward.app.content.index.ContentSearchSort
import com.pocketsteward.app.content.index.IndexedFileSearchResult
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.dedupe.DuplicateGroup
import com.pocketsteward.app.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Every browse-or-decide surface, as one destination.
 *
 * `[device]` Through M6 each of these had its own back handler wired to
 * `reset()`, so leaving any of them discarded the scan. Back here is a stack
 * pop and nothing else.
 */
@Composable
fun ReviewScreen(viewModel: ScanViewModel, onBack: () -> Unit) {
    val state by viewModel.review.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()

    val title = when (val current = state) {
        is ScanUiState.FileListReview -> current.title
        is ScanUiState.ContentSearchReview -> current.title
        is ScanUiState.IndexedContentSearchReview -> current.title
        is ScanUiState.CoherenceAuditReview -> "Coherence audit"
        is ScanUiState.DuplicateReview -> "Duplicates"
        is ScanUiState.ProtectFolders -> "Protect folders"
        else -> "Review"
    }

    ScanFlowScaffold(
        title = title,
        onBack = onBack,
        error = error,
        onDismissError = viewModel::dismissError,
        busy = busy,
    ) { contentModifier ->
        when (val current = state) {
            is ScanUiState.FileListReview -> FileListReview(current, contentModifier)
            is ScanUiState.ContentSearchReview -> ContentSearchReview(current, contentModifier)
            is ScanUiState.IndexedContentSearchReview -> IndexedContentSearchReview(
                state = current,
                onSortChange = viewModel::setIndexedSearchSort,
                onFiltersChange = viewModel::setIndexedSearchFilters,
                onResetFilters = viewModel::resetIndexedSearchFilters,
                onRefresh = { viewModel.refreshIndexedSearch(current) },
                onSaveSearch = { name -> viewModel.saveIndexedSearch(current, name) },
                modifier = contentModifier,
            )
            is ScanUiState.CoherenceAuditReview -> CoherenceAuditReview(
                state = current,
                onBuildProposal = { includeSubfolders ->
                    viewModel.proposeSemanticOrganization(current, includeSubfolders)
                },
                modifier = contentModifier,
            )
            is ScanUiState.DuplicateReview -> DuplicateReview(
                state = current,
                onTrashDuplicates = { viewModel.proposeTrashDuplicates(current) },
                onBack = onBack,
                modifier = contentModifier,
            )
            is ScanUiState.ProtectFolders -> ProtectFolders(
                state = current,
                onToggleProtection = { folder -> viewModel.proposeToggleProtection(current, folder) },
                modifier = contentModifier,
            )
            else -> EmptyState("Nothing to review.", contentModifier)
        }
    }
}

@Composable
private fun FileListReview(state: ScanUiState.FileListReview, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        ScreenHeadline(
            text = state.title,
            supporting = "${state.records.size} file(s) · browse only, nothing planned yet",
        )
        if (state.records.isEmpty()) {
            EmptyState("Nothing here matched.")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.hairline),
        ) {
            items(state.records, key = { it.stableRef }) { record -> FileRow(record) }
        }
    }
}

/**
 * `[device]` The size column used to wrap one character per line, because
 * both columns were unconstrained and the name took everything. The name gets
 * the flexible width and one line; the size gets its own fixed share and never
 * wraps.
 */
@Composable
private fun FileRow(record: FileRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.hairline),
        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        Text(
            text = record.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatBytes(record.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ContentSearchReview(state: ScanUiState.ContentSearchReview, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        val details = buildString {
            append("${state.matches.size} match(es) · ${state.inspectedFiles} readable file(s) inspected")
            if (state.unsupportedFiles > 0) append(" · ${state.unsupportedFiles} unsupported")
            if (state.failedFiles > 0) append(" · ${state.failedFiles} failed")
            if (state.truncatedResults) append(" · inspection limit reached, results may be incomplete")
        }
        ScreenHeadline(
            text = state.title,
            supporting = "$details · local read-only inspection, nothing planned",
        )
        if (state.matches.isEmpty()) {
            EmptyState("No readable file contents matched “${state.query}”.")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            items(state.matches, key = { it.record.stableRef }) { match ->
                ContentMatchCard(match)
            }
        }
    }
}

@Composable
private fun ContentMatchCard(match: ContentMatch) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = match.record.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (match.pageNumber != null) {
                Text(
                    text = buildString {
                        append("Page ${match.pageNumber}")
                        if (match.ocr) append(" · OCR")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
            }
            Text(
                text = match.snippet,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
            Text(
                text = match.record.stableRef,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndexedContentSearchReview(
    state: ScanUiState.IndexedContentSearchReview,
    onSortChange: (ContentSearchSort) -> Unit,
    onFiltersChange: (ContentSearchFilters) -> Unit,
    onResetFilters: () -> Unit,
    onRefresh: () -> Unit,
    onSaveSearch: (String) -> Unit,
    modifier: Modifier,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var saveDialogOpen by remember { mutableStateOf(false) }
    var expandedRefs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saveName by remember { mutableStateOf("") }

    val visible = state.visibleResults
    val refresh = state.refreshSummary
    val indexDetails = buildString {
        append("${visible.size} shown · ${state.allResults.size} matching file(s)")
        append(" · ${refresh.reused} reused")
        if (refresh.extracted > 0) append(" · ${refresh.extracted} refreshed")
        if (refresh.failed > 0) append(" · ${refresh.failed} failed")
        if (!state.indexComplete) append(" · index incomplete")
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        item {
            ScreenHeadline(
                text = state.title,
                supporting = "$indexDetails · local persistent index",
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.base)) {
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
                                Text("Sort")
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                ContentSearchSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = state.sort == option,
                                                    onClick = null,
                                                )
                                                Text(option.label())
                                            }
                                        },
                                        onClick = {
                                            onSortChange(option)
                                            sortMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { filterSheetOpen = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(ContentSearchPresentation.filtersLabel(state.filters.activeCount))
                        }

                        Column {
                            TextButton(onClick = { moreMenuOpen = true }) {
                                Text("More")
                            }
                            DropdownMenu(
                                expanded = moreMenuOpen,
                                onDismissRequest = { moreMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Refresh index") },
                                    onClick = {
                                        moreMenuOpen = false
                                        onRefresh()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Save search") },
                                    onClick = {
                                        moreMenuOpen = false
                                        saveDialogOpen = true
                                    },
                                )
                                if (state.filters.activeCount > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Reset filters") },
                                        onClick = {
                                            moreMenuOpen = false
                                            onResetFilters()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Sorted by ${state.sort.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.hairline),
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyState(
                    if (state.allResults.isEmpty()) {
                        "No indexed file contents matched “${state.query}”."
                    } else {
                        "No results match the current filters."
                    },
                )
            }
        } else {
            items(visible, key = { it.stableRef }) { result ->
                IndexedContentResultCard(
                    result = result,
                    expanded = result.stableRef in expandedRefs,
                    onToggleExpanded = {
                        expandedRefs = expandedRefs.toMutableSet().apply {
                            if (result.stableRef in this) remove(result.stableRef) else add(result.stableRef)
                        }
                    },
                )
            }
        }
    }

    if (filterSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { filterSheetOpen = false },
        ) {
            SearchFilterSheet(
                state = state,
                onFiltersChange = onFiltersChange,
                onResetFilters = onResetFilters,
                onDone = { filterSheetOpen = false },
            )
        }
    }

    if (saveDialogOpen) {
        AlertDialog(
            onDismissRequest = { saveDialogOpen = false },
            title = { Text("Save search") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("Name") },
                    placeholder = { Text("Pain documents") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveSearch(saveName)
                        saveName = ""
                        saveDialogOpen = false
                    },
                    enabled = saveName.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogOpen = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SearchFilterSheet(
    state: ScanUiState.IndexedContentSearchReview,
    onFiltersChange: (ContentSearchFilters) -> Unit,
    onResetFilters: () -> Unit,
    onDone: () -> Unit,
) {
    val scroll = rememberScrollState()
    val filters = state.filters

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = Spacing.screen, vertical = Spacing.tight),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onDone) { Text("Done") }
        }

        SearchFilterSection("Source")
        if (state.availableRoots.size <= 1) {
            Text(
                state.availableRoots.firstOrNull()?.substringAfterLast('/') ?: "Current search scope",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.availableRoots.forEach { root ->
                val selected = root in filters.sourceRoots
                CheckFilterRow(
                    label = root.substringAfterLast('/').ifBlank { root },
                    checked = selected,
                    onToggle = {
                        val next = filters.sourceRoots.toMutableSet().apply {
                            if (selected) remove(root) else add(root)
                        }
                        onFiltersChange(filters.copy(sourceRoots = next))
                    },
                )
            }
            TextButton(
                onClick = { onFiltersChange(filters.copy(sourceRoots = emptySet())) },
            ) { Text("All source folders") }
        }

        SearchFilterSection("Type")
        state.availableCategories.forEach { category ->
            val selected = category in filters.categories
            CheckFilterRow(
                label = category.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                checked = selected,
                onToggle = {
                    val next = filters.categories.toMutableSet().apply {
                        if (selected) remove(category) else add(category)
                    }
                    onFiltersChange(filters.copy(categories = next))
                },
            )
        }
        TextButton(
            onClick = { onFiltersChange(filters.copy(categories = emptySet())) },
        ) { Text("All types") }

        SearchFilterSection("Content source")
        ContentSearchProvenance.entries.forEach { provenance ->
            RadioFilterRow(
                label = provenance.label(),
                selected = filters.provenance == provenance,
                onSelect = { onFiltersChange(filters.copy(provenance = provenance)) },
            )
        }

        SearchFilterSection("Extension")
        if (state.availableExtensions.isEmpty()) {
            Text("No extensions available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.availableExtensions.forEach { ext ->
                val selected = ext in filters.extensions
                CheckFilterRow(
                    label = ".$ext",
                    checked = selected,
                    onToggle = {
                        val next = filters.extensions.toMutableSet().apply {
                            if (selected) remove(ext) else add(ext)
                        }
                        onFiltersChange(filters.copy(extensions = next))
                    },
                )
            }
            TextButton(
                onClick = { onFiltersChange(filters.copy(extensions = emptySet())) },
            ) { Text("Any extension") }
        }

        SearchFilterSection("Modified")
        val now = System.currentTimeMillis()
        val dateChoices = listOf(
            "Any date" to null,
            "Last 7 days" to now - TimeUnit.DAYS.toMillis(7),
            "Last 30 days" to now - TimeUnit.DAYS.toMillis(30),
            "Last 6 months" to now - TimeUnit.DAYS.toMillis(183),
            "Last year" to now - TimeUnit.DAYS.toMillis(365),
        )
        dateChoices.forEach { (label, after) ->
            val selected = if (after == null) {
                filters.modifiedAfter == null && filters.modifiedBefore == null
            } else {
                filters.modifiedAfter?.let { abs(it - after) < TimeUnit.MINUTES.toMillis(5) } == true
            }
            RadioFilterRow(
                label = label,
                selected = selected,
                onSelect = {
                    onFiltersChange(
                        filters.copy(
                            modifiedAfter = after,
                            modifiedBefore = null,
                        ),
                    )
                },
            )
        }

        SearchFilterSection("Size")
        val mib = 1024L * 1024L
        val sizeChoices = listOf(
            Triple("Any size", null, null),
            Triple("Under 1 MiB", null, mib),
            Triple("1–10 MiB", mib, 10 * mib),
            Triple("10–100 MiB", 10 * mib, 100 * mib),
            Triple("Over 100 MiB", 100 * mib, null),
        )
        sizeChoices.forEach { (label, min, max) ->
            RadioFilterRow(
                label = label,
                selected = filters.minSizeBytes == min && filters.maxSizeBytes == max,
                onSelect = {
                    onFiltersChange(filters.copy(minSizeBytes = min, maxSizeBytes = max))
                },
            )
        }

        SearchFilterSection("Folder or path")
        OutlinedTextField(
            value = filters.pathContains,
            onValueChange = { onFiltersChange(filters.copy(pathContains = it)) },
            label = { Text("Path contains") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (filters.activeCount > 0) {
            TextButton(
                onClick = onResetFilters,
                modifier = Modifier.padding(top = Spacing.base),
            ) {
                Text("Reset all filters (${filters.activeCount})")
            }
        }
    }
}

@Composable
private fun SearchFilterSection(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = Spacing.base, bottom = Spacing.tight))
    Text(title, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun CheckFilterRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.hairline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = Spacing.tight))
    }
}

@Composable
private fun RadioFilterRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = Spacing.hairline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = Spacing.tight))
    }
}

@Composable
private fun IndexedContentResultCard(
    result: IndexedFileSearchResult,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = result.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(result.extension.ifBlank { "file" }.uppercase())
                    append(" · ")
                    append(formatBytes(result.sizeBytes))
                    result.modifiedAt?.let {
                        append(" · ")
                        append(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
            result.parentRef?.let { parent ->
                Text(
                    text = parent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
            }

            val shown = if (expanded) result.snippets else result.snippets.take(1)
            shown.forEachIndexed { index, snippet ->
                if (snippet.pageNumber != null || snippet.ocr) {
                    Text(
                        text = buildString {
                            snippet.pageNumber?.let { append("Page $it") }
                            if (snippet.ocr) {
                                if (isNotEmpty()) append(" · ")
                                append("OCR")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.tight),
                    )
                }
                Text(
                    text = ContentSearchPresentation.quotedSnippet(snippet.text),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = if (index == 0) Spacing.hairline else Spacing.tight),
                )
            }

            if (result.extraSnippetCount > 0) {
                TextButton(onClick = onToggleExpanded) {
                    Text(
                        if (expanded) {
                            "Show less"
                        } else {
                            "Show ${result.extraSnippetCount} more match${if (result.extraSnippetCount == 1) "" else "es"}"
                        },
                    )
                }
            }
        }
    }
}

private fun ContentSearchSort.label(): String = when (this) {
    ContentSearchSort.RELEVANCE -> "Relevance"
    ContentSearchSort.NAME_ASC -> "Name A–Z"
    ContentSearchSort.NAME_DESC -> "Name Z–A"
    ContentSearchSort.MODIFIED_NEWEST -> "Newest"
    ContentSearchSort.MODIFIED_OLDEST -> "Oldest"
    ContentSearchSort.LARGEST -> "Largest"
    ContentSearchSort.SMALLEST -> "Smallest"
    ContentSearchSort.PATH -> "Folder"
}

private fun ContentSearchProvenance.label(): String = when (this) {
    ContentSearchProvenance.ANY -> "Any"
    ContentSearchProvenance.PDF -> "PDF"
    ContentSearchProvenance.OCR -> "OCR"
    ContentSearchProvenance.EXTRACTED_TEXT -> "Text"
}

@Composable
private fun CoherenceAuditReview(
    state: ScanUiState.CoherenceAuditReview,
    onBuildProposal: (Boolean) -> Unit,
    modifier: Modifier,
) {
    var includeSubfolders by remember { mutableStateOf(false) }

    val model = state.modelName?.let { " · $it" } ?: ""
    val limitNote = if (state.limited) " · bounded sample, not every readable file was analyzed" else ""
    val proposalCandidates = state.rows.count {
        it.suggestedGroup?.isNotBlank() == true &&
            it.classification in setOf(
                com.pocketsteward.app.ai.CoherenceClass.QUESTIONABLE,
                com.pocketsteward.app.ai.CoherenceClass.DOES_NOT_BELONG,
            )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        item {
            ScreenHeadline(
                text = "Coherence audit · ${state.scopeLabel}",
                supporting = "${state.rows.size} classified · ${state.skippedUnreadable} unreadable/skipped$model$limitNote · read-only",
            )
        }

        if (state.rows.isEmpty()) {
            item { EmptyState("The model returned no usable classifications.") }
        } else {
            items(state.rows, key = { it.record.stableRef }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Spacing.base)) {
                        Text(
                            text = row.record.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = row.classification.name.replace('_', ' '),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                        Text(
                            text = row.reason,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                        row.suggestedGroup?.let { suggestion ->
                            Text(
                                text = "Suggested group: $suggestion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.hairline),
                            )
                        }
                    }
                }
            }
        }

        if (proposalCandidates > 0) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight)) {
                    Column(modifier = Modifier.padding(Spacing.base)) {
                        Text(
                            "Build a safe proposal",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Pocket Steward will re-check these findings against the current scan, keep destinations inside each source root, and open the normal checkbox preview. Nothing moves yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Include files already inside folders")
                                Text(
                                    if (includeSubfolders) {
                                        "On · nested files may be included in the proposal."
                                    } else {
                                        "Off · preserve existing human organization."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = includeSubfolders,
                                onCheckedChange = { includeSubfolders = it },
                            )
                        }
                        Button(
                            onClick = { onBuildProposal(includeSubfolders) },
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.base),
                        ) {
                            Text("Build organization proposal")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateReview(
    state: ScanUiState.DuplicateReview,
    onTrashDuplicates: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val kept = state.groups.size
        val wouldTrash = state.groups.sumOf { it.extras.size }
        ScreenHeadline(
            text = if (state.groups.isEmpty()) "No duplicates" else "$kept duplicate set(s)",
            // Spec item 4: the assertion that matters, stated before
            // approval. A preview of 3,316 rows is not reviewable; one line
            // saying every group keeps exactly one copy is.
            supporting = if (state.groups.isEmpty()) {
                "Nothing under ${state.scopeLabel} matched by SHA-256."
            } else {
                "$kept kept · $wouldTrash would move to Trash · exact SHA-256 match only"
            },
        )

        if (state.groups.isEmpty()) {
            EmptyState("Exact match only. Nothing here shares a hash with anything else.")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            items(state.groups, key = { it.sha256 }) { group -> DuplicateGroupCard(group) }
        }

        ActionRow {
            Button(onClick = onTrashDuplicates) { Text("Propose trashing extra copies") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = "${group.members.size} identical copies",
                style = MaterialTheme.typography.titleSmall,
            )
            // Which copy survives is decided by KeeperSelector, not by scan
            // order, and it's labelled here so the choice is visible before
            // anything is proposed rather than discovered afterwards.
            Text(
                text = "Keeping",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.tight),
            )
            Text(text = group.keeper.stableRef, style = MaterialTheme.typography.bodySmall)
            if (group.extras.isNotEmpty()) {
                Text(
                    text = "Would move to Trash",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = Spacing.tight),
                )
                group.extras.forEach { extra ->
                    Text(
                        text = "${extra.stableRef} (${formatBytes(extra.sizeBytes)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtectFolders(
    state: ScanUiState.ProtectFolders,
    onToggleProtection: (ProtectableFolder) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ScreenHeadline(
            text = "Folders under ${state.scopeLabel}",
            supporting = "A protected folder and everything inside it is off limits to Smart cleanup, " +
                "whatever the subfolder setting says.",
        )

        if (state.folders.isEmpty()) {
            EmptyState("No subfolders here.")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            items(state.folders, key = { it.stableRef }) { folder ->
                // Both directions are tappable. Unprotecting trashes the
                // marker rather than deleting it, so it obeys the same rule
                // as everything else here and is recoverable from Trash.
                Card(onClick = { onToggleProtection(folder) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Spacing.base)) {
                        Text(
                            text = if (state.scopes.size == 1) {
                                folder.displayName
                            } else {
                                "${folder.scope.label} / ${folder.displayName}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (folder.isProtected) {
                                "Protected · ${folder.fileCount} file(s) · tap to remove protection"
                            } else {
                                "${folder.fileCount} file(s) · tap to protect"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
