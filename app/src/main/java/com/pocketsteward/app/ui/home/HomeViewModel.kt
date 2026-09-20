package com.pocketsteward.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.saved.SavedWorkflow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    taskRunDao: TaskRunDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val recentTasks: StateFlow<List<TaskRun>> = taskRunDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedWorkflows: StateFlow<List<SavedWorkflow>> = settingsRepository.savedWorkflows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteSavedWorkflow(id: String) {
        viewModelScope.launch { settingsRepository.deleteSavedWorkflow(id) }
    }
}
