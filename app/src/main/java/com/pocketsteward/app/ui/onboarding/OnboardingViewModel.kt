package com.pocketsteward.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.launch

class OnboardingViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    fun onBroadAccessGranted() {
        viewModelScope.launch {
            settingsRepository.setStorageAccessMode(StorageAccessMode.DIRECT)
        }
    }

    fun onSafTreeSelected(treeUri: String) {
        viewModelScope.launch {
            settingsRepository.setSafTreeUri(treeUri)
            settingsRepository.setStorageAccessMode(StorageAccessMode.SAF)
        }
    }

    fun onStorageAccessInvalid() {
        viewModelScope.launch {
            settingsRepository.clearStorageAccessChoice()
        }
    }
}
