package com.pocketsteward.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.settings.PrivacySettings
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.data.settings.StorageAccessState
import com.pocketsteward.app.rules.ProjectKeyword
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    val privacySettings: StateFlow<PrivacySettings> = settingsRepository.privacySettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacySettings())

    val storageAccessState: StateFlow<StorageAccessState> = settingsRepository.storageAccessState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageAccessState())

    val projectKeywords: StateFlow<List<ProjectKeyword>> = settingsRepository.projectKeywords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Parses one "term=folder" pair per line (blank lines and lines without
     * an `=` are silently dropped rather than rejected — this is a plain
     * text field, not a form with validation errors) and persists the
     * result. [RuleEngine][com.pocketsteward.app.rules.RuleEngine] reads
     * this list fresh each time Smart Cleanup runs, so a save here takes
     * effect on the next cleanup proposal, no restart needed.
     */
    /**
     * Clears the stored access choice so onboarding offers broad-vs-SAF again
     * on next launch. Deliberately does not revoke anything at the OS level —
     * it can't, and pretending otherwise would be the same dishonesty the
     * dead Home tiles were. It only forgets *this app's* recorded preference.
     */
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
