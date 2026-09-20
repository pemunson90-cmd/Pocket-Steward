package com.pocketsteward.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.ai.AgentModel
import com.pocketsteward.app.ai.AgentModelAvailability
import com.pocketsteward.app.ai.AgentModelDownloadState
import com.pocketsteward.app.data.settings.PrivacySettings
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.data.settings.StorageAccessState
import com.pocketsteward.app.data.settings.UiSettings
import com.pocketsteward.app.rules.ProjectKeyword
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelStatusUi(
    val availability: AgentModelAvailability? = null,
    val downloading: Boolean = false,
    val bytesDownloaded: Long = 0,
    val bytesToDownload: Long? = null,
    val error: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val agentModel: AgentModel,
) : ViewModel() {

    val privacySettings: StateFlow<PrivacySettings> = settingsRepository.privacySettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacySettings())

    val storageAccessState: StateFlow<StorageAccessState> = settingsRepository.storageAccessState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageAccessState())

    val uiSettings: StateFlow<UiSettings> = settingsRepository.uiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiSettings())

    val projectKeywords: StateFlow<List<ProjectKeyword>> = settingsRepository.projectKeywords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _modelStatus = MutableStateFlow(ModelStatusUi())
    val modelStatus: StateFlow<ModelStatusUi> = _modelStatus

    init {
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            _modelStatus.value = try {
                ModelStatusUi(availability = agentModel.availability())
            } catch (t: Throwable) {
                ModelStatusUi(error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** User-initiated only. Merely enabling AI never starts this download. */
    fun downloadModel() {
        if (_modelStatus.value.downloading) return
        viewModelScope.launch {
            var total: Long? = null
            agentModel.download().collect { state ->
                _modelStatus.value = when (state) {
                    is AgentModelDownloadState.Started -> {
                        total = state.bytesToDownload
                        ModelStatusUi(
                            availability = AgentModelAvailability.DOWNLOADING,
                            downloading = true,
                            bytesToDownload = total,
                        )
                    }
                    is AgentModelDownloadState.Progress -> ModelStatusUi(
                        availability = AgentModelAvailability.DOWNLOADING,
                        downloading = true,
                        bytesDownloaded = state.bytesDownloaded,
                        bytesToDownload = total,
                    )
                    AgentModelDownloadState.Completed -> ModelStatusUi(
                        availability = AgentModelAvailability.AVAILABLE,
                    )
                    is AgentModelDownloadState.Failed -> ModelStatusUi(
                        availability = AgentModelAvailability.DOWNLOADABLE,
                        error = state.message,
                    )
                }
            }
            refreshModelStatus()
        }
    }

    fun setAdvancedModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAdvancedModeEnabled(enabled) }
    }

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

    fun clearStorageAccessChoice() {
        viewModelScope.launch { settingsRepository.clearStorageAccessChoice() }
    }

    fun setProjectKeywordsFromText(text: String) {
        val keywords = text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    ProjectKeyword(term = parts[0].trim(), projectFolder = parts[1].trim())
                } else {
                    null
                }
            }
            .toList()
        viewModelScope.launch { settingsRepository.setProjectKeywords(keywords) }
    }
}
