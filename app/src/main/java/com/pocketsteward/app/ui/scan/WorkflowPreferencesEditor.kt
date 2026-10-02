package com.pocketsteward.app.ui.scan

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.pocketsteward.app.saved.WorkflowDestination
import com.pocketsteward.app.saved.WorkflowPreferences
import com.pocketsteward.app.saved.WorkflowSelection

internal val WorkflowPreferencesSaver = listSaver<WorkflowPreferences, Any>(
    save = { listOf(it.metadata, it.content, it.images, it.selection.name, it.destination.name, it.destinationFolder.orEmpty()) },
    restore = { values ->
        val destination = WorkflowDestination.valueOf(values[4] as String)
        val folder = (values[5] as String).takeIf { path -> path.isNotEmpty() || destination == WorkflowDestination.CHOSEN_FOLDER }
        WorkflowPreferences(values[0] as Boolean, values[1] as Boolean, values[2] as Boolean,
            WorkflowSelection.valueOf(values[3] as String), destination, folder)
    },
)

@Composable
internal fun WorkflowPreferencesEditor(value: WorkflowPreferences, direct: Boolean, onChange: (WorkflowPreferences) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = if (direct) directPrimaryTreePath(uri) ?: uri.toString() else uri.toString()
            onChange(value.copy(destination = WorkflowDestination.CHOSEN_FOLDER, destinationFolder = path))
        }
    }
    Column {
        Text("Evidence for this workflow", style = MaterialTheme.typography.titleSmall)
        Text("Uses only inspection types enabled in global Privacy settings.", style = MaterialTheme.typography.bodySmall)
        WorkflowEvidenceSwitch("Metadata and archive/package facts", value.metadata) { onChange(value.copy(metadata = it)) }
        WorkflowEvidenceSwitch("Document text and OCR", value.content) { onChange(value.copy(content = it)) }
        WorkflowEvidenceSwitch("Image labels and OCR", value.images) { onChange(value.copy(images = it)) }
        Text("Review selection", style = MaterialTheme.typography.titleSmall)
        WorkflowSelection.entries.forEach { policy -> TextButton(onClick = { onChange(value.copy(selection = policy)) }) {
            Text(if (value.selection == policy) "✓ ${policy.label}" else policy.label)
        } }
        Text("Destination for new groups", style = MaterialTheme.typography.titleSmall)
        Text("Existing project homes stay preferred. Explicit move/copy requests keep the destination in the request.", style = MaterialTheme.typography.bodySmall)
        WorkflowDestination.entries.forEach { policy -> TextButton(onClick = { onChange(value.copy(destination = policy,
            destinationFolder = if (policy == WorkflowDestination.CHOSEN_FOLDER) value.destinationFolder.orEmpty() else null)) }) {
            Text(if (value.destination == policy) "✓ ${policy.label}" else policy.label)
        } }
        if (value.destination == WorkflowDestination.CHOSEN_FOLDER) {
            TextButton(onClick = { picker.launch(null) }) { Text("Choose destination folder") }
            OutlinedTextField(value = value.destinationFolder.orEmpty(), onValueChange = { onChange(value.copy(destinationFolder = it.trim())) },
                label = { Text(if (direct) "Shared-storage folder path" else "Folder URI inside the scanned tree") }, modifier = Modifier.fillMaxWidth())
            if (direct && value.destinationFolder?.startsWith("content://") == true) {
                Text("This provider needs selected-folder access. Use a shared-storage folder here or change the access mode.", style = MaterialTheme.typography.bodySmall)
            }
            Text("The folder and current access are checked again whenever this workflow runs.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WorkflowEvidenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
}

/** Only Android's primary shared-storage provider has a direct-path mapping here. */
internal fun directPrimaryTreePath(uri: Uri): String? = runCatching {
    require(uri.authority == "com.android.externalstorage.documents")
    val id = DocumentsContract.getTreeDocumentId(uri)
    require(id.startsWith("primary:"))
    val relative = id.removePrefix("primary:").trim('/')
    require(relative.split('/').none { it in setOf(".", "..") } && relative.none { it.isISOControl() })
    @Suppress("DEPRECATION") val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
    root + if (relative.isEmpty()) "" else "/$relative"
}.getOrNull()
