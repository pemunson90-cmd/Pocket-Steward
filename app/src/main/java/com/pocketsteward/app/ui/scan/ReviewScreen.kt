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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.dedupe.DuplicateGroup
import com.pocketsteward.app.ui.theme.Spacing

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
