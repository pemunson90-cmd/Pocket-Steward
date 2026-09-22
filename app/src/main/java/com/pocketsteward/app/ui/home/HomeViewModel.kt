package com.pocketsteward.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.TaskJournalProgress
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.saved.LastScanSession
import com.pocketsteward.app.saved.SavedWorkflow
import com.pocketsteward.app.saved.SavedSearch
import com.pocketsteward.app.scheduled.PendingCleanupSuggestion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    taskRunDao: TaskRunDao,
    mutationRecordDao: MutationRecordDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val recentTasks: StateFlow<List<TaskRun>> = taskRunDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val taskProgress: StateFlow<Map<Long, TaskJournalProgress>> =
        mutationRecordDao.observeTaskProgress()
            .map { rows -> rows.associateBy(TaskJournalProgress::taskRunId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val savedWorkflows: StateFlow<List<SavedWorkflow>> = settingsRepository.savedWorkflows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedSearches: StateFlow<List<SavedSearch>> = settingsRepository.savedSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastScanSession: StateFlow<LastScanSession?> = settingsRepository.lastScanSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingCleanupSuggestion: StateFlow<PendingCleanupSuggestion?> =
        settingsRepository.pendingCleanupSuggestion
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun deleteSavedWorkflow(id: String) {
        viewModelScope.launch { settingsRepository.deleteSavedWorkflow(id) }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch { settingsRepository.deleteSavedSearch(id) }
    }

    fun dismissCleanupSuggestion() {
        viewModelScope.launch { settingsRepository.clearPendingCleanupSuggestion() }
    }
}
