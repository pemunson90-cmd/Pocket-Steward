package com.pocketsteward.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketsteward.app.saved.DocumentTopicRules
import com.pocketsteward.app.saved.DocumentTopicTemplate

@Composable
internal fun DocumentTopicSettingsCard(viewModel: SettingsViewModel) {
    val rules by viewModel.documentTopicRules.collectAsState()
    val templates by viewModel.namedDocumentTopicTemplates.collectAsState()
    val message by viewModel.documentTopicMessage.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var originalName by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf("") }
    var terms by rememberSaveable { mutableStateOf("") }
    var templateName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(rules.encode()) {
        if (originalName != null && rules.topics.none { it.name == originalName } && rules.topics.any { it.name == name }) originalName = name
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Document topics", style = MaterialTheme.typography.titleMedium)
            Text("${rules.topics.size} active topics. Uses inspected document text. Project ownership takes priority; topic destinations wait for your confirmation.")
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide topic settings" else "Manage topics and reusable templates") }
            if (expanded) {
                Text("At least two distinct keywords or phrases must appear in the inspected text. Several matching topics require a choice.", style = MaterialTheme.typography.bodySmall)
                rules.topics.forEach { topic ->
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { originalName = topic.name; name = topic.name; folder = topic.folder; terms = topic.terms.joinToString(", ") }, modifier = Modifier.weight(1f)) {
                            Text("Edit ${topic.name} → ${topic.folder}")
                        }
                        TextButton(onClick = { viewModel.removeDocumentTopicRule(topic.name) }) { Text("Remove") }
                    }
                }
                TextButton(onClick = { originalName = null; name = ""; folder = ""; terms = "" }) { Text("Add topic") }
                OutlinedTextField(name, { name = it }, label = { Text("Topic name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(folder, { folder = it }, label = { Text("Relative destination folder") }, supportingText = { Text("Under Documents or this workflow's chosen base. Use / for up to four levels.") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(terms, { terms = it }, label = { Text("Keywords or phrases, separated by commas") }, modifier = Modifier.fillMaxWidth())
                val valid = runCatching { DocumentTopicTemplate(name.trim(), folder.trim(), terms.split(',').map { it.trim() }).validate() }.isSuccess
                Button(onClick = { viewModel.saveDocumentTopicRule(originalName, name, folder, terms) }, enabled = valid) { Text("Save topic") }
                OutlinedTextField(templateName, { templateName = it }, label = { Text("Reusable template name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { viewModel.saveNamedDocumentTopicTemplate(templateName) }, enabled = templateName.isNotBlank()) { Text("Save current topics as template") }
                templates.forEach { saved ->
                    Row {
                        TextButton(onClick = { viewModel.setDocumentTopicRules(saved.rules.encode()) }) { Text("Use ${saved.name}") }
                        TextButton(onClick = { viewModel.removeNamedDocumentTopicTemplate(saved.name) }) { Text("Remove template") }
                    }
                }
                TextButton(onClick = { viewModel.setDocumentTopicRules(DocumentTopicRules.Defaults.encode()) }) { Text("Restore default topics") }
                TextButton(onClick = { viewModel.setDocumentTopicRules("") }) { Text("Turn off topic suggestions") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
