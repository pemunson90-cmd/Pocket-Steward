package com.pocketsteward.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.settings.PrivacySettings
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.data.settings.StorageAccessState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    val privacySettings: StateFlow<PrivacySettings> = settingsRepository.privacySettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacySettings())

    val storageAccessState: StateFlow<StorageAccessState> = settingsRepository.storageAccessState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageAccessState())

    fun setMetadataIndexingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMetadataIndexingEnabled(enabled) }
    }

    fun setContentInspectionEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setContentInspectionEnabled(enabled) }
    }

    fun setImageAnalysisEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setImageAnalysisEnabled(enabled) }
    }

    fun setOnDeviceAiEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setOnDeviceAiEnabled(enabled) }
    }
}
