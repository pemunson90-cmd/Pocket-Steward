package com.pocketsteward.app.ui.scan

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.pocketsteward.app.navigation.AdaptiveLayoutPolicy
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import com.pocketsteward.app.saved.FavoriteDestination
import com.pocketsteward.app.semantic.DestinationPolicy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.ui.history.ManifestFileActions
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
    val favoriteDestinations by viewModel.favoriteDestinations.collectAsState()
    val recentFolders by viewModel.recentFolders.collectAsState()

    val title = when (val current = state) {
        is ScanUiState.FileListReview -> current.title
        is ScanUiState.ContentSearchReview -> current.title
        is ScanUiState.IndexedContentSearchReview -> current.title
        is ScanUiState.CoherenceAuditReview -> "Document organizer"
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
                onPauseIndex = viewModel::pauseContentIndexing,
                onSaveSearch = { name -> viewModel.saveIndexedSearch(current, name) },
                modifier = contentModifier,
            )
            is ScanUiState.CoherenceAuditReview -> CoherenceAuditReview(
                state = current,
                favoriteDestinations = favoriteDestinations,
                recentFolders = recentFolders,
                onBuildProposal = { includeSubfolders, destinationPolicy, explicitPath ->
                    viewModel.proposeSemanticOrganization(
                        review = current,
                        includeSubfolders = includeSubfolders,
                        destinationPolicy = destinationPolicy,
                        explicitDestinationPath = explicitPath,
                    )
                },
                modifier = contentModifier,
            )
            is ScanUiState.DuplicateReview -> DuplicateReview(
                state = current,
                onTrashDuplicates = { viewModel.proposeTrashDuplicates(current) },
                onBack = onBack,
                modifier = contentModifier,
            )
            is ScanUiState.SimilarReview -> SimilarReview(
                state = current,
                modifier = contentModifier,
            )
            is ScanUiState.ImageAnalysisReview -> ImageAnalysisReview(
                state = current,
                modifier = contentModifier,
            )
            is ScanUiState.RichMetadataReview -> RichMetadataReview(
                state = current,
                modifier = contentModifier,
            )
            is ScanUiState.ArtifactExportReview -> ArtifactExportReview(
                state = current,
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
            items(state.records, key = { it.stableRef }) { record ->
                FileRow(
                    record = record,
                    explanation = state.explanationByRef[record.stableRef],
                )
            }
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
private fun FileRow(
    record: FileRecord,
    explanation: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.hairline),
        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            explanation?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
            }
        }
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
    onPauseIndex: () -> Unit,
    onSaveSearch: (String) -> Unit,
    modifier: Modifier,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var saveDialogOpen by remember { mutableStateOf(false) }
    var previewSheetOpen by remember { mutableStateOf(false) }
    var expandedRefs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saveName by remember { mutableStateOf("") }
    var selectedRef by rememberSaveable { mutableStateOf<String?>(null) }
    val resultListState = rememberLazyListState()

    val visible = state.visibleResults
    val refresh = state.refreshSummary
    val selected = visible.firstOrNull { it.stableRef == selectedRef } ?: visible.firstOrNull()

    val indexDetails = buildString {
        append("${visible.size} shown · ${state.allResults.size} matching file(s)")
        append(" · ${refresh.reused} reused")
        if (refresh.extracted > 0) append(" · ${refresh.extracted} refreshed")
        if (refresh.failed > 0) append(" · ${refresh.failed} failed")
        if (!state.indexComplete) append(" · index incomplete")
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val expanded = AdaptiveLayoutPolicy.useTwoPane(maxWidth.value)

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "$indexDetails · local persistent index",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.tight),
            )

            SearchToolbar(
                state = state,
                sortMenuOpen = sortMenuOpen,
                onSortMenuOpenChange = { sortMenuOpen = it },
                moreMenuOpen = moreMenuOpen,
                onMoreMenuOpenChange = { moreMenuOpen = it },
                onSortChange = onSortChange,
                onOpenFilters = { filterSheetOpen = true },
                onRefresh = onRefresh,
                onPauseIndex = onPauseIndex,
                onSave = { saveDialogOpen = true },
                onResetFilters = onResetFilters,
            )

            if (visible.isEmpty()) {
                EmptyState(
                    if (state.allResults.isEmpty()) {
                        "No indexed file contents matched “${state.query}”."
                    } else {
                        "No results match the current filters."
                    },
                    Modifier.weight(1f),
                )
            } else if (expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = Spacing.tight),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                ) {
                    LazyColumn(
                        state = resultListState,
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
                    ) {
                        items(visible, key = { it.stableRef }) { result ->
                            IndexedContentResultCard(
                                result = result,
                                expanded = result.stableRef in expandedRefs,
                                selected = selected?.stableRef == result.stableRef,
                                onSelect = { selectedRef = result.stableRef },
                                onToggleExpanded = {
                                    expandedRefs = expandedRefs.toMutableSet().apply {
                                        if (result.stableRef in this) remove(result.stableRef) else add(result.stableRef)
                                    }
                                },
                            )
                        }
                    }

                    selected?.let { result ->
                        SearchResultPreview(
                            result = result,
                            modifier = Modifier
                                .weight(0.48f)
                                .fillMaxHeight(),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = resultListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = Spacing.tight),
                    verticalArrangement = Arrangement.spacedBy(Spacing.tight),
                ) {
                    items(visible, key = { it.stableRef }) { result ->
                        IndexedContentResultCard(
                            result = result,
                            expanded = result.stableRef in expandedRefs,
                            selected = selectedRef == result.stableRef,
                            onSelect = {
                                selectedRef = result.stableRef
                                previewSheetOpen = true
                            },
                            onToggleExpanded = {
                                expandedRefs = expandedRefs.toMutableSet().apply {
                                    if (result.stableRef in this) remove(result.stableRef) else add(result.stableRef)
                                }
                            },
                        )
                    }
                }
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

    if (previewSheetOpen && selected != null) {
        ModalBottomSheet(
            onDismissRequest = { previewSheetOpen = false },
        ) {
            SearchResultPreview(
                result = selected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen, vertical = Spacing.tight),
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
private fun SearchToolbar(
    state: ScanUiState.IndexedContentSearchReview,
    sortMenuOpen: Boolean,
    onSortMenuOpenChange: (Boolean) -> Unit,
    moreMenuOpen: Boolean,
    onMoreMenuOpenChange: (Boolean) -> Unit,
    onSortChange: (ContentSearchSort) -> Unit,
    onOpenFilters: () -> Unit,
    onRefresh: () -> Unit,
    onPauseIndex: () -> Unit,
    onSave: () -> Unit,
    onResetFilters: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { onSortMenuOpenChange(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sort")
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { onSortMenuOpenChange(false) },
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
                                    onSortMenuOpenChange(false)
                                },
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onOpenFilters,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        ContentSearchPresentation.filtersLabel(state.filters.activeCount),
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Column {
                    IconButton(onClick = { onMoreMenuOpenChange(true) }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More search actions")
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { onMoreMenuOpenChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Refresh index") },
                            onClick = {
                                onMoreMenuOpenChange(false)
                                onRefresh()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Save search") },
                            onClick = {
                                onMoreMenuOpenChange(false)
                                onSave()
                            },
                        )
                        if (!state.indexComplete && state.indexEligible > 0) {
                            DropdownMenuItem(
                                text = { Text("Pause indexing") },
                                onClick = {
                                    onMoreMenuOpenChange(false)
                                    onPauseIndex()
                                },
                            )
                        }
                        if (state.filters.activeCount > 0) {
                            DropdownMenuItem(
                                text = { Text("Reset filters") },
                                onClick = {
                                    onMoreMenuOpenChange(false)
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
            if (!state.indexComplete && state.indexEligible > 0) {
                LinearProgressIndicator(
                    progress = {
                        state.indexProcessed.toFloat() /
                            state.indexEligible.coerceAtLeast(1).toFloat()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                )
                Text(
                    text = "Indexing in background · ${state.indexProcessed} of ${state.indexEligible} files processed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.hairline),
                )
            }
        }
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
                val isSelected = root in filters.sourceRoots
                CheckFilterRow(
                    label = root.substringAfterLast('/').ifBlank { root },
                    checked = isSelected,
                    onToggle = {
                        val next = filters.sourceRoots.toMutableSet().apply {
                            if (isSelected) remove(root) else add(root)
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
            val isSelected = category in filters.categories
            CheckFilterRow(
                label = category.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                checked = isSelected,
                onToggle = {
                    val next = filters.categories.toMutableSet().apply {
                        if (isSelected) remove(category) else add(category)
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
                val isSelected = ext in filters.extensions
                CheckFilterRow(
                    label = ".$ext",
                    checked = isSelected,
                    onToggle = {
                        val next = filters.extensions.toMutableSet().apply {
                            if (isSelected) remove(ext) else add(ext)
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
            val isSelected = if (after == null) {
                filters.modifiedAfter == null && filters.modifiedBefore == null
            } else {
                filters.modifiedAfter?.let { abs(it - after) < TimeUnit.MINUTES.toMillis(5) } == true
            }
            RadioFilterRow(
                label = label,
                selected = isSelected,
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
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = result.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                SearchSnippet(snippet, topPadding = if (index == 0) Spacing.hairline else Spacing.tight)
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

@Composable
private fun SearchResultPreview(
    result: IndexedFileSearchResult,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(Spacing.base),
    ) {
        Text(result.displayName, style = MaterialTheme.typography.headlineSmall)
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
                parent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
        }

        Button(
            onClick = { openIndexedFile(context, result) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.base),
        ) {
            Text("Open file")
        }

        Text(
            "Matches",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = Spacing.section, bottom = Spacing.hairline),
        )
        result.snippets.forEach { snippet ->
            SearchSnippet(snippet, topPadding = Spacing.tight)
        }
    }
}

@Composable
private fun SearchSnippet(
    snippet: com.pocketsteward.app.content.index.IndexedSearchSnippet,
    topPadding: androidx.compose.ui.unit.Dp,
) {
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
            modifier = Modifier.padding(top = topPadding),
        )
    }
    Text(
        text = highlightedSearchSnippet(snippet.text),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = Spacing.hairline),
    )
}

@Composable
private fun highlightedSearchSnippet(raw: String): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append("“")
        var cursor = 0
        while (cursor < raw.length) {
            val start = raw.indexOf('⟦', cursor)
            if (start < 0) {
                append(raw.substring(cursor))
                break
            }
            append(raw.substring(cursor, start))
            val end = raw.indexOf('⟧', start + 1)
            if (end < 0) {
                append(raw.substring(start + 1))
                break
            }
            withStyle(
                SpanStyle(
                    color = primary,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(raw.substring(start + 1, end))
            }
            cursor = end + 1
        }
        append("”")
    }
}

private fun openDirectFile(
    context: Context,
    path: String,
    displayName: String,
    extension: String,
) {
    val uri = if (path.startsWith("content://")) {
        Uri.parse(path)
    } else {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "That file is no longer available.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrElse {
            Toast.makeText(
                context,
                "Pocket Steward could not expose this file to another app.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
    }

    val opened = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ContentSearchPresentation.mimeType(extension))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open $displayName"))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, "No installed app could open this file.", Toast.LENGTH_SHORT).show()
    }
}

private fun openIndexedFile(
    context: Context,
    result: IndexedFileSearchResult,
) {
    openDirectFile(
        context = context,
        path = result.stableRef,
        displayName = result.displayName,
        extension = result.extension,
    )
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
    favoriteDestinations: List<FavoriteDestination>,
    recentFolders: List<String>,
    onBuildProposal: (Boolean, DestinationPolicy, String?) -> Unit,
    modifier: Modifier,
) {
    val directScope = state.scopes.all { it.root is FileRef.Direct }
    var includeSubfolders by remember { mutableStateOf(false) }
    var destinationPolicy by remember(directScope) {
        mutableStateOf(
            if (directScope) DestinationPolicy.RECOMMENDED_DOCUMENTS
            else DestinationPolicy.ROOT_LOCAL,
        )
    }
    var explicitDestination by remember { mutableStateOf("") }
    val context = LocalContext.current

    val model = state.modelName?.let { " · $it" } ?: ""
    val proposalCandidates = state.rows.count {
        it.suggestedGroup?.isNotBlank() == true &&
            it.classification in setOf(
                com.pocketsteward.app.ai.CoherenceClass.QUESTIONABLE,
                com.pocketsteward.app.ai.CoherenceClass.DOES_NOT_BELONG,
            )
    }
    val questionable = state.rows.count {
        it.classification == com.pocketsteward.app.ai.CoherenceClass.QUESTIONABLE
    }
    val outliers = state.rows.count {
        it.classification == com.pocketsteward.app.ai.CoherenceClass.DOES_NOT_BELONG
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        item {
            ScreenHeadline(
                text = "Document audit · ${state.scopeLabel}",
                supporting = buildString {
                    append("${state.sampledDocuments} representative document(s) sampled from ${state.eligibleDocuments} readable")
                    append(" · ${state.indexedExcerpts} from index")
                    if (state.freshExtractions > 0) append(" · ${state.freshExtractions} freshly read")
                    if (state.modelFailures > 0) append(" · ${state.modelFailures} model misses")
                    append(model)
                    append(" · local audit")
                },
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.base)) {
                    Text("Audit summary", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.rows.size} classified · $questionable questionable · $outliers outlier(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Spacing.hairline),
                    )
                    Text(
                        "The sample is stratified across folders and file types instead of taking the first paths. Tap any result to open the source file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.hairline),
                    )
                }
            }
        }

        if (state.rows.isEmpty()) {
            item { EmptyState("The model returned no usable classifications.") }
        } else {
            items(state.rows, key = { it.record.stableRef }) { row ->
                Card(
                    onClick = {
                        openDirectFile(
                            context = context,
                            path = row.record.stableRef,
                            displayName = row.record.displayName,
                            extension = row.record.extension,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(Spacing.base)) {
                        Text(
                            text = row.record.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = row.classification.name.replace('_', ' '),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                        Text(
                            text = row.reason.ifBlank { "No explanation returned." },
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
                        Text(
                            text = row.record.parentRef.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                    }
                }
            }
        }

        if (state.eligibleDocuments > 0) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight)) {
                    Column(modifier = Modifier.padding(Spacing.base)) {
                        Text("Build organization proposal", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append("Model outliers: $proposalCandidates. ")
                                append("The proposal also evaluates readable documents using project keywords, repeated filename/title signals, indexed content, and model advice. ")
                                if (directScope) {
                                    append("Choose where one-level groups should live.")
                                } else {
                                    append("Selected-folder mode keeps every proposed group inside the granted tree.")
                                }
                                append(" Nothing moves until the next preview is approved.")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )

                        Text(
                            "Destination",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = Spacing.base),
                        )

                        if (directScope) {
                            RadioFilterRow(
                                label = "Recommended Documents",
                                selected = destinationPolicy == DestinationPolicy.RECOMMENDED_DOCUMENTS,
                                onSelect = { destinationPolicy = DestinationPolicy.RECOMMENDED_DOCUMENTS },
                            )
                            RadioFilterRow(
                                label = "Keep inside current scan root",
                                selected = destinationPolicy == DestinationPolicy.ROOT_LOCAL,
                                onSelect = { destinationPolicy = DestinationPolicy.ROOT_LOCAL },
                            )
                            RadioFilterRow(
                                label = "Choose explicit existing folder",
                                selected = destinationPolicy == DestinationPolicy.EXPLICIT_FOLDER,
                                onSelect = { destinationPolicy = DestinationPolicy.EXPLICIT_FOLDER },
                            )
                            if (favoriteDestinations.isNotEmpty()) {
                                Text(
                                    "Favorites",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = Spacing.tight),
                                )
                                favoriteDestinations.forEach { favorite ->
                                    OutlinedButton(
                                        onClick = {
                                            destinationPolicy = DestinationPolicy.EXPLICIT_FOLDER
                                            explicitDestination = favorite.path
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.hairline),
                                    ) {
                                        Text(
                                            "${favorite.name} · ${favorite.path}",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (destinationPolicy == DestinationPolicy.EXPLICIT_FOLDER) {
                                if (recentFolders.isNotEmpty()) {
                                    Text(
                                        "Recent folders",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(top = Spacing.tight),
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                                    ) {
                                        items(recentFolders.take(8)) { path ->
                                            FilterChip(
                                                selected = explicitDestination.trimEnd('/') == path.trimEnd('/'),
                                                onClick = { explicitDestination = path },
                                                label = {
                                                    Text(path.substringAfterLast('/').ifBlank { path })
                                                },
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = explicitDestination,
                                    onValueChange = { explicitDestination = it },
                                    label = { Text("Destination folder path") },
                                    placeholder = { Text("/storage/emulated/0/Documents") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.hairline),
                                )
                            }
                        } else {
                            Text(
                                "Inside the selected Android document tree",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.hairline),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.base),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Include files already inside folders")
                                Text(
                                    if (includeSubfolders) {
                                        "On · nested files may be proposed."
                                    } else {
                                        "Off · existing human folder structure is preserved."
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
                            onClick = {
                                onBuildProposal(
                                    includeSubfolders,
                                    destinationPolicy,
                                    explicitDestination.takeIf {
                                        directScope &&
                                            destinationPolicy == DestinationPolicy.EXPLICIT_FOLDER
                                    },
                                )
                            },
                            enabled = !directScope ||
                                destinationPolicy != DestinationPolicy.EXPLICIT_FOLDER ||
                                explicitDestination.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.base),
                        ) {
                            Text("Review proposed moves")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichMetadataReview(
    state: ScanUiState.RichMetadataReview,
    modifier: Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        item {
            ScreenHeadline(
                text = "Rich metadata · ${state.scopeLabel}",
                supporting = "${state.entries.size} file(s) inspected · Level-1 local metadata",
            )
        }

        if (state.entries.isEmpty()) {
            item { EmptyState("No supported image, media, APK, PDF, or ZIP files were available.") }
        }

        items(state.entries, key = { it.record.stableRef }) { entry ->
            val record = entry.record
            Card(
                onClick = {
                    openDirectFile(
                        context = context,
                        path = record.stableRef,
                        displayName = record.displayName,
                        extension = record.extension,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(Spacing.base)) {
                    Text(
                        record.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = buildList {
                        if (record.width != null && record.height != null) {
                            add("${record.width} × ${record.height}")
                        }
                        record.durationMs?.let { add("Duration ${formatDuration(it)}") }
                        record.apkPackageName?.let { pkg ->
                            add("APK $pkg${record.apkVersionName?.let { " · $it" }.orEmpty()}")
                        }
                        entry.pdfPageCount?.let { add("PDF · $it page(s)") }
                        entry.archiveEntryCount?.let { add("ZIP · $it entries") }
                        entry.exifCamera?.let { add("Camera: $it") }
                        entry.exifOrientation?.let { add("Orientation: $it") }
                    }
                    details.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                    }
                    if (entry.archiveSample.isNotEmpty()) {
                        Text(
                            "Archive sample: " + entry.archiveSample.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun ImageAnalysisReview(
    state: ScanUiState.ImageAnalysisReview,
    modifier: Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        item {
            ScreenHeadline(
                text = "Image understanding · ${state.scopeLabel}",
                supporting = buildString {
                    append("${state.insights.size} analyzed successfully · ${state.attempted} attempted")
                    if (state.limited) append(" · bounded recent-image sample")
                    append(" · local ML Kit model")
                },
            )
        }

        if (state.insights.isEmpty()) {
            item { EmptyState("No usable image labels were produced.") }
        }

        items(state.insights, key = { it.stableRef }) { insight ->
            Card(
                onClick = {
                    openDirectFile(
                        context = context,
                        path = insight.stableRef,
                        displayName = insight.displayName,
                        extension = insight.displayName.substringAfterLast('.', ""),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(Spacing.base)) {
                    Text(
                        insight.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (insight.likelyScreenshot) {
                        Text(
                            "Likely screenshot",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                    }
                    if (insight.labels.isNotEmpty()) {
                        Text(
                            insight.labels.joinToString(" · ") { label ->
                                "${label.label} ${(label.confidence * 100).toInt()}%"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                    } else {
                        Text(
                            "No label exceeded the confidence threshold.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.hairline),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactExportReview(
    state: ScanUiState.ArtifactExportReview,
    modifier: Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        item {
            ScreenHeadline(
                text = state.title,
                supporting = "${state.paths.size} verified artifact(s) · ${state.errors.size} failure(s)",
            )
        }
        items(state.paths, key = { it }) { path ->
            Card(
                onClick = { ManifestFileActions.open(context, path) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(Spacing.base)) {
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                    Text(
                        path.substringBeforeLast('/', missingDelimiterValue = path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                    ) {
                        TextButton(onClick = { ManifestFileActions.open(context, path) }) {
                            Text("Open")
                        }
                        TextButton(onClick = { ManifestFileActions.share(context, path) }) {
                            Text("Share")
                        }
                        TextButton(onClick = { ManifestFileActions.showContainingFolder(context, path) }) {
                            Text("Show folder")
                        }
                    }
                }
            }
        }
        if (state.errors.isNotEmpty()) {
            item { SectionHeader("Failures") }
            items(state.errors) { error ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Spacing.base),
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarReview(
    state: ScanUiState.SimilarReview,
    modifier: Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
    ) {
        item {
            ScreenHeadline(
                text = "Similar files · ${state.scopeLabel}",
                supporting = "${state.groups.size} group(s) · ${state.imagesAnalyzed} images analyzed · ${state.documentsAnalyzed} indexed documents analyzed · review only",
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "These are perceptual/semantic near-duplicates, not byte-identical duplicates. Pocket Steward will not offer automatic trash actions from this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.base),
                )
            }
        }
        if (state.groups.isEmpty()) {
            item { EmptyState("No near-duplicate groups were found in the analyzed files.") }
        }
        state.groups.forEachIndexed { index, group ->
            item(key = "similar-$index") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Spacing.base)) {
                        Text(
                            text = "${group.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${group.records.size} similar files",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        group.records.forEach { record ->
                            Card(
                                onClick = {
                                    openDirectFile(
                                        context = context,
                                        path = record.stableRef,
                                        displayName = record.displayName,
                                        extension = record.extension,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = Spacing.hairline),
                            ) {
                                Column(modifier = Modifier.padding(Spacing.tight)) {
                                    Text(
                                        record.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        record.parentRef.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
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

        val canTrash = state.scopes.all { it.root is FileRef.Direct }
        ActionRow {
            if (canTrash) {
                Button(onClick = onTrashDuplicates) { Text("Propose trashing extra copies") }
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        if (!canTrash) {
            Text(
                "Read-only review: switch to full file-manager access if you want Pocket Steward to propose moving duplicate copies to Trash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
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
