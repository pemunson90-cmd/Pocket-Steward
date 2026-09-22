package com.pocketsteward.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.ui.theme.Spacing

/**
 * The frame every scan-flow destination sits in.
 *
 * Exists so the six destinations cannot drift apart: one title bar, one
 * gutter, one place the error banner and the busy indicator appear. Before
 * M7 all of this was inlined in a single 748-line file, which is how six
 * different paddings and four different back behaviours got in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanFlowScaffold(
    title: String,
    onBack: () -> Unit,
    error: String? = null,
    onDismissError: () -> Unit = {},
    busy: ScanUiState.Working? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.screen, vertical = Spacing.tight),
        ) {
            // Above the content, never instead of it. An error that replaced
            // the screen was how a failed action used to cost a rescan.
            error?.let { message ->
                ErrorBanner(message = message, onDismiss = onDismissError)
            }
            busy?.let { BusyIndicator(it) }
            content(Modifier.weight(1f))
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = Spacing.hairline)) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Every long operation's feedback. Before this existed, tapping an action left
 * the previous screen on display for the whole operation — on a 13,000 file
 * scope that is indistinguishable from a dead button.
 *
 * It sits above the destination rather than replacing it, so the results stay
 * visible while a plan is being generated from them.
 */
@Composable
fun BusyIndicator(state: ScanUiState.Working) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight)) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            val processed = state.processed
            val total = state.total
            if (processed != null && total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { processed.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${state.label}: $processed of $total",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.tight),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = state.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.tight),
                )
            }
            state.detail?.let {
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

/**
 * The headline of a destination: one line, one weight, so a screen has a
 * subject rather than a list of equally-loud sentences. "Task complete",
 * "4 folder(s) created" and "2657 moved" all rendering at the same size is
 * what made the completion screen unreadable.
 */
@Composable
fun ScreenHeadline(text: String, supporting: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = Spacing.base)) {
        Text(text = text, style = MaterialTheme.typography.headlineSmall)
        supporting?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.hairline),
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(top = Spacing.base, bottom = Spacing.hairline),
    )
}

/**
 * The row of actions that closes a destination. Pinned below the scrolling
 * content rather than inside it, which is what stops a long list running
 * underneath Approve.
 */
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.base, bottom = Spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** What a destination shows when its list is genuinely empty, rather than nothing at all. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(vertical = Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun operationSummary(operation: PlannedOperation): String = when (operation) {
    is PlannedOperation.CreateDirectory -> "Create folder: ${operation.name}"
    is PlannedOperation.Move -> "Move ${operation.source.shortPath()} to ${operation.destination.shortPath()}"
    is PlannedOperation.Copy -> "Copy ${operation.source.shortPath()} to ${operation.destination.shortPath()}"
    is PlannedOperation.Rename -> "Rename to ${operation.newName}"
    is PlannedOperation.Trash -> "Trash ${operation.source.shortPath()}"
    is PlannedOperation.WriteTextFile -> "Write ${operation.name} into ${operation.parent.shortPath()}"
}

/**
 * The last two path segments (parent folder + filename), not just the
 * filename: a move's source and destination usually share a filename, so
 * showing only the basename made every row in the preview read
 * "X.apk to X.apk" with no way to tell what actually changed.
 */
fun FileRef.shortPath(): String = when (this) {
    is FileRef.Direct ->
        absolutePath.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
    is FileRef.Saf ->
        documentUri.substringAfterLast('/').let { android.net.Uri.decode(it) }
    is FileRef.Child -> {
        val parentLabel = parent.shortPath().substringAfterLast('/')
        listOf(parentLabel, name).filter { it.isNotBlank() }.joinToString("/")
    }
}

fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

