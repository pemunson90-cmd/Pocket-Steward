package com.pocketsteward.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.R
import com.pocketsteward.app.storage.StorageAccessMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository) }
        },
    )

    val privacy by viewModel.privacySettings.collectAsState()
    val storageAccess by viewModel.storageAccessState.collectAsState()

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
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text = stringResource(R.string.settings_storage_access_section))
            Text(
                text = when (storageAccess.mode) {
                    StorageAccessMode.DIRECT -> stringResource(R.string.settings_storage_mode_broad)
                    StorageAccessMode.SAF -> stringResource(R.string.settings_storage_mode_saf)
                    null -> "Not granted"
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

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
