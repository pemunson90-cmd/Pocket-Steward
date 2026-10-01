package com.pocketsteward.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketsteward.app.MainActivity
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.ui.theme.PocketStewardTheme

/** Share intake grants source read authority only; all writes use approved, durable Copy tasks. */
class ShareIntakeActivity : ComponentActivity() {
    private val model: ShareIntakeViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val incoming = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }.distinct()
        if (incoming.isEmpty()) { finish(); return }
        incoming.forEach { uri ->
            if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        model.load(incoming.map(Uri::toString))
        setContent {
            PocketStewardTheme {
                val state by model.state.collectAsState()
                val tasks by model.tasks.collectAsState(initial = emptyList())
                var project by rememberSaveable { mutableStateOf("") }
                var selected by remember(state.files) { mutableStateOf<Set<String>>(state.files.mapTo(hashSetOf()) { it.stableRef }) }
                var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)).toString()
                        }.onSuccess { model.prepare(it, project, selected) }.onFailure { pickerError = it.message }
                    }
                }
                Scaffold { padding ->
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("Import shared files", style = MaterialTheme.typography.headlineMedium)
                            Text("Copies preserve the originals. Choose a destination, review every copy, then approve. Keep this screen open while importing if the source app supplied temporary access.")
                            Text("Some Android providers require selecting their storage root so protection above destination folders can be verified.", style = MaterialTheme.typography.bodySmall)
                            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            (pickerError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        if (state.taskId != null) {
                            item {
                                Text("Task ${state.taskId} · ${tasks.firstOrNull { it.id == state.taskId }?.summary ?: "Queued"}")
                                Text("Progress, recovery and undo are available in Tasks. If temporary source access expires, share the files again rather than retrying without access.")
                            }
                        } else if (state.preview == null) {
                            item {
                                OutlinedTextField(project, { project = it }, label = { Text("Project title (optional)") }, supportingText = { Text("A title keeps documents and images in one project. Leave blank to use type folders.") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.loading)
                                Text("${selected.size} of ${state.files.size} selected")
                                Button(onClick = { pickerError = null; picker.launch(null) }, enabled = selected.isNotEmpty() && !state.loading) { Text("Choose destination and build preview") }
                            }
                            items(state.files, key = { it.stableRef }) { file ->
                                Row {
                                    Checkbox(file.stableRef in selected, { checked -> selected = if (checked) selected + file.stableRef else selected - file.stableRef }, enabled = !state.loading)
                                    Column { Text(file.displayName); Text("${file.sizeBytes} bytes", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        } else {
                            val preview = requireNotNull(state.preview)
                            item {
                                Text("Review ${preview.files.size} copies", style = MaterialTheme.typography.titleLarge)
                                Text("Destination: ${preview.root.rawValue()}")
                                Button(onClick = model::approve, enabled = !state.loading) { Text("Approve and copy ${preview.files.size} files") }
                                OutlinedButton(onClick = model::edit, enabled = !state.loading) { Text("Change selection or destination") }
                            }
                            items(preview.operations) { operation ->
                                when (operation) {
                                    is PlannedOperation.Copy -> Text("Copy ${preview.files.first { it.stableRef == operation.source.rawValue() }.displayName} → ${relativePath(operation.destination)}")
                                    is PlannedOperation.CreateDirectory -> Text("Create folder: ${relativePath(FileRef.Child(operation.parent, operation.name))}")
                                    else -> Unit
                                }
                            }
                        }
                        item { OutlinedButton(onClick = { startActivity(Intent(this@ShareIntakeActivity, MainActivity::class.java)) }) { Text("Open Pocket Steward") } }
                    }
                }
            }
        }
    }
}

private fun relativePath(ref: FileRef): String {
    val parts = mutableListOf<String>()
    var current = ref
    while (current is FileRef.Child) { parts += current.name; current = current.parent }
    return parts.asReversed().joinToString(" / ").ifEmpty { ref.rawValue() }
}
