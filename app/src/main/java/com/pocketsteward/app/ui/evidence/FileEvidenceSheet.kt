package com.pocketsteward.app.ui.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.evidence.EvidenceRequest
import com.pocketsteward.app.evidence.FileEvidenceReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Same view in Filing, Search, Ask and Coherence. Opening it never creates a task. */
@Composable
fun FileEvidenceButton(request: EvidenceRequest, modifier: Modifier = Modifier) {
    var open by rememberSaveable(request.ref) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) { Text("Inspect evidence") }
    if (open) FileEvidenceSheet(request, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEvidenceSheet(request: EvidenceRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val privacy by container.settingsRepository.privacySettings.collectAsState(initial = com.pocketsteward.app.data.settings.PrivacySettings())
    val access by container.settingsRepository.storageAccessState.collectAsState(initial = null)
    var report by remember(request) { mutableStateOf<FileEvidenceReport?>(null) }
    var failure by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(request, privacy, access) {
        report = null; failure = null
        try {
            report = withContext(Dispatchers.IO) { withTimeout(90_000) { container.fileEvidenceInspector.inspect(request) } }
        } catch (_: TimeoutCancellationException) { failure = "Inspection timed out. Try again after current analysis finishes." }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = error.message ?: "Inspection is unavailable." }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val loaded = report
        when {
            loaded != null -> FileEvidenceReportContent(loaded)
            failure != null -> Column(Modifier.padding(16.dp)) { Text("File evidence"); Text(requireNotNull(failure)) }
            else -> Column(Modifier.padding(16.dp)) { Text("Inspecting this file…"); CircularProgressIndicator() }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Close evidence") }
    }
}

@Composable
internal fun FileEvidenceReportContent(report: FileEvidenceReport) {
    val maximum = LocalConfiguration.current.screenHeightDp.dp * 0.75f
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = maximum).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(report.name, style = MaterialTheme.typography.titleLarge); Text(report.status, style = MaterialTheme.typography.bodyMedium) }
        itemsIndexed(report.sections) { _, section ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    section.complete?.let { Text(if (it) "Inspection complete for this source" else "Partial inspection · more evidence may exist", style = MaterialTheme.typography.labelMedium) }
                    section.lines.filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
