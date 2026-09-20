package com.pocketsteward.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.ai.AgentModelAvailability
import com.pocketsteward.app.R
import com.pocketsteward.app.storage.StorageAccessMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenTrash: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository, container.agentModel) }
        },
    )

    val privacy by viewModel.privacySettings.collectAsState()
    val storageAccess by viewModel.storageAccessState.collectAsState()
    val storedKeywords by viewModel.projectKeywords.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()

    // Seeded once from the stored value, not re-synced on every emission —
    // otherwise an in-progress edit would get overwritten by the DataStore
    // flow re-emitting the value this same screen just wrote.
    var keywordsText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storedKeywords) {
        if (keywordsText == null) {
            keywordsText = storedKeywords.joinToString("\n") { "${it.term}=${it.projectFolder}" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = stringResource(R.string.settings_storage_access_section))
            Text(
                text = when (storageAccess.mode) {
                    StorageAccessMode.DIRECT -> stringResource(R.string.settings_storage_mode_broad)
                    StorageAccessMode.SAF -> stringResource(R.string.settings_storage_mode_saf)
                    null -> "Not granted"
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            if (storageAccess.mode == StorageAccessMode.SAF) {
                Text(
                    text = "In folder-only mode Pocket Steward can scan and browse, but can't move, trash, or read file contents.",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Before this, once broad access was granted onboarding
            // auto-advanced past the choice forever and revoking All Files
            // Access in system settings was the only way back.
            Card(
                onClick = viewModel::clearStorageAccessChoice,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_change_storage_mode),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            Card(onClick = onOpenTrash, modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_open_trash),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_privacy_section),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )

            SettingsSwitchRow(
                label = stringResource(R.string.settings_metadata_indexing),
                checked = privacy.metadataIndexingEnabled,
                onCheckedChange = viewModel::setMetadataIndexingEnabled,
            )
            SettingsSwitchRow(
                label = stringResource(R.string.settings_content_inspection),
                checked = privacy.contentInspectionEnabled,
                onCheckedChange = viewModel::setContentInspectionEnabled,
            )
            Text(
                text = "Reads supported text/code and Office documents only when you ask. Extracted text stays in memory and is not added to the file index. PDF text is not enabled yet.",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsSwitchRow(
                label = stringResource(R.string.settings_image_analysis),
                checked = privacy.imageAnalysisEnabled,
                onCheckedChange = viewModel::setImageAnalysisEnabled,
            )
            SettingsSwitchRow(
                label = stringResource(R.string.settings_on_device_ai),
                checked = privacy.onDeviceAiEnabled,
                onCheckedChange = viewModel::setOnDeviceAiEnabled,
            )
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Gemini Nano", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    val statusText = when (modelStatus.availability) {
                        AgentModelAvailability.AVAILABLE -> "Available on device"
                        AgentModelAvailability.DOWNLOADABLE -> "Available to download"
                        AgentModelAvailability.DOWNLOADING -> "Downloading"
                        AgentModelAvailability.UNAVAILABLE -> "Unavailable on this device/configuration"
                        null -> "Checking availability"
                    }
                    Text(statusText, modifier = Modifier.padding(top = 4.dp))
                    modelStatus.error?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                    if (modelStatus.downloading) {
                        val total = modelStatus.bytesToDownload
                        if (total != null && total > 0L) {
                            LinearProgressIndicator(
                                progress = { (modelStatus.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                    if (modelStatus.availability == AgentModelAvailability.DOWNLOADABLE && !modelStatus.downloading) {
                        Button(
                            onClick = viewModel::downloadModel,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Download Gemini Nano")
                        }
                    }
                    Text(
                        "Pocket Steward will never start the model download merely because AI is enabled.",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

            Text(
                text = "Project keywords",
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                text = "One per line, as term=folder. A filename containing the term groups into that folder during Smart Cleanup — e.g. Leaseworld=Leaseworld.",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = keywordsText ?: "",
                onValueChange = { keywordsText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Card(
                onClick = { viewModel.setProjectKeywordsFromText(keywordsText ?: "") },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(text = "Save keywords", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
