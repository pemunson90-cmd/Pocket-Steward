package com.pocketsteward.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.executor.UndoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UndoUiState {
    data object Idle : UndoUiState
    data class InProgress(val taskRunId: Long) : UndoUiState
    data class Done(val summary: UndoSummary) : UndoUiState
    data class Error(val message: String) : UndoUiState
}

class HistoryViewModel(
    taskRunDao: TaskRunDao,
    private val settingsRepository: SettingsRepository,
    private val container: AppContainer,
) : ViewModel() {

    val taskRuns: StateFlow<List<TaskRun>> = taskRunDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _undoState = MutableStateFlow<UndoUiState>(UndoUiState.Idle)
    val undoState: StateFlow<UndoUiState> = _undoState

    fun undo(taskRunId: Long) {
        viewModelScope.launch {
            _undoState.value = UndoUiState.InProgress(taskRunId)
            try {
                val mode = settingsRepository.storageAccessState.first().mode
                if (mode == null) {
                    _undoState.value = UndoUiState.Error("No storage access granted.")
                    return@launch
                }
                val executor = container.undoExecutor(mode)
                val summary = withContext(Dispatchers.IO) { executor.undo(taskRunId) }
                _undoState.value = UndoUiState.Done(summary)
            } catch (t: Throwable) {
                _undoState.value = UndoUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun dismissUndoResult() {
        _undoState.value = UndoUiState.Idle
    }
}
