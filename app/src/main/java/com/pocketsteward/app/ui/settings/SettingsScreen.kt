package com.pocketsteward.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.ai.AgentModelAvailability
import com.pocketsteward.app.storage.StorageAccessMode

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenTrash: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    agentModel = container.agentModel,
                    loadContentIndexOverview = container::contentIndexOverview,
                    clearContentIndexCache = container::clearContentIndex,
                )
            }
        },
    )

    val privacy by viewModel.privacySettings.collectAsState()
    val storageAccess by viewModel.storageAccessState.collectAsState()
    val uiSettings by viewModel.uiSettings.collectAsState()
    val storedKeywords by viewModel.projectKeywords.collectAsState()
    val favoriteDestinations by viewModel.favoriteDestinations.collectAsState()
    val correctionRules by viewModel.correctionRules.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val contentIndexStatus by viewModel.contentIndexStatus.collectAsState()

    var keywordsText by remember { mutableStateOf<String?>(null) }
    var favoritesText by remember { mutableStateOf<String?>(null) }
    var correctionsText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storedKeywords) {
        if (keywordsText == null) {
            keywordsText = storedKeywords.joinToString("\n") { "${it.term}=${it.projectFolder}" }
        }
    }
    LaunchedEffect(favoriteDestinations) {
        if (favoritesText == null) {
            favoritesText = favoriteDestinations.joinToString("\n") { "${it.name}=${it.path}" }
        }
    }
    LaunchedEffect(correctionRules) {
        if (correctionsText == null) {
            correctionsText = correctionRules.joinToString("\n") { "${it.term}=${it.destinationFolder}" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionTitle("Storage")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = when (storageAccess.mode) {
                        StorageAccessMode.DIRECT -> "Full storage access"
                        StorageAccessMode.SAF -> "Selected-folder access"
                        null -> "Storage access not configured"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when (storageAccess.mode) {
                        StorageAccessMode.DIRECT -> "Pocket Steward can scan and organize shared storage."
                        StorageAccessMode.SAF -> "Pocket Steward can browse the selected folder but file-changing actions are limited."
                        null -> "Choose how Pocket Steward can reach your files."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = viewModel::clearStorageAccessChoice,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text("Change access")
                }
            }
        }

        Card(
            onClick = onOpenTrash,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Trash", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Review files Pocket Steward moved aside. Pocket Steward never permanently deletes them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SectionTitle("Privacy & intelligence")
        SettingsSwitchRow(
            label = "Inspect document contents",
            supporting = "Local and on demand. Supports text, Office documents, PDFs, and scanned-PDF OCR.",
            checked = privacy.contentInspectionEnabled,
            onCheckedChange = viewModel::setContentInspectionEnabled,
        )
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Content search index", style = MaterialTheme.typography.titleMedium)
                val index = contentIndexStatus.overview
                Text(
                    text = when {
                        contentIndexStatus.loading -> "Checking local index…"
                        contentIndexStatus.error != null -> "Index status unavailable"
                        index.documentCount == 0 -> "No cached document content yet"
                        else -> "${index.documentCount} files · ${index.segmentCount} searchable segment(s) · ${index.rootCount} root(s)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = if (privacy.contentInspectionEnabled) {
                        "Search content stays on this device. Unchanged files are reused on later searches."
                    } else {
                        "Content inspection is off. Any existing local cache remains private until you clear it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                contentIndexStatus.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                contentIndexStatus.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(
                    onClick = viewModel::clearContentIndex,
                    enabled = !contentIndexStatus.loading && index.documentCount > 0,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text("Clear content index")
                }
            }
        }

        SettingsSwitchRow(
            label = "On-device intelligence",
            supporting = "Allows local semantic analysis when a compatible model is available.",
            checked = privacy.onDeviceAiEnabled,
            onCheckedChange = viewModel::setOnDeviceAiEnabled,
        )

        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("On-device model", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when (modelStatus.availability) {
                        AgentModelAvailability.AVAILABLE -> "Ready"
                        AgentModelAvailability.DOWNLOADABLE -> "Download required"
                        AgentModelAvailability.DOWNLOADING -> "Downloading"
                        AgentModelAvailability.UNAVAILABLE -> "Not available on this device"
                        null -> "Checking availability"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                modelStatus.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (modelStatus.downloading) {
                    val total = modelStatus.bytesToDownload
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (modelStatus.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                }
                if (modelStatus.availability == AgentModelAvailability.DOWNLOADABLE && !modelStatus.downloading) {
                    Button(
                        onClick = viewModel::downloadModel,
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Text("Download on-device model")
                    }
                }
            }
        }

        SectionTitle("Organization preferences")
        Text(
            text = "Favorite destinations",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "One per line as name=/absolute/path. Favorite roots appear in organization destination choices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = favoritesText ?: "",
            onValueChange = { favoritesText = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(
            onClick = { viewModel.setFavoriteDestinationsFromText(favoritesText ?: "") },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Save favorite destinations")
        }

        Text(
            text = "Learned correction rules",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "One per line as filename-term=group. These outrank project keywords and model suggestions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = correctionsText ?: "",
            onValueChange = { correctionsText = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(
            onClick = { viewModel.setCorrectionRulesFromText(correctionsText ?: "") },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Save correction rules")
        }

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp))
        SettingsSwitchRow(
            label = "Advanced",
            supporting = "Show power-user controls and implementation details.",
            checked = uiSettings.advancedModeEnabled,
            onCheckedChange = viewModel::setAdvancedModeEnabled,
        )

        if (uiSettings.advancedModeEnabled) {
            SectionTitle("Advanced")
            Text(
                text = "Storage mode: ${storageAccess.mode ?: "not configured"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Model provider: Gemini Nano via AICore",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            SettingsSwitchRow(
                label = "Metadata indexing",
                supporting = "Keep the local metadata index updated.",
                checked = privacy.metadataIndexingEnabled,
                onCheckedChange = viewModel::setMetadataIndexingEnabled,
            )
            SettingsSwitchRow(
                label = "Image analysis",
                supporting = "Reserved for future visual analysis.",
                checked = privacy.imageAnalysisEnabled,
                onCheckedChange = viewModel::setImageAnalysisEnabled,
            )

            Text(
                text = "Project keyword rules",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "One rule per line as term=folder. Filename matches can override ordinary type grouping.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = keywordsText ?: "",
                onValueChange = { keywordsText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(
                onClick = { viewModel.setProjectKeywordsFromText(keywordsText ?: "") },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Save rules")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
