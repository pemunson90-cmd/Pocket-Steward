package com.pocketsteward.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.executor.UndoExecutor
import com.pocketsteward.app.executor.UndoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HistoryActionState {
    data object Idle : HistoryActionState
    data class Undoing(val taskRunId: Long) : HistoryActionState
    data class Done(val summary: UndoSummary) : HistoryActionState
    data class Error(val message: String) : HistoryActionState
}

class HistoryViewModel(
    taskRunDao: TaskRunDao,
    private val undoExecutor: UndoExecutor,
) : ViewModel() {
    val tasks: StateFlow<List<TaskRun>> = taskRunDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _actionState = MutableStateFlow<HistoryActionState>(HistoryActionState.Idle)
    val actionState: StateFlow<HistoryActionState> = _actionState

    fun undo(taskRunId: Long) {
        viewModelScope.launch {
            _actionState.value = HistoryActionState.Undoing(taskRunId)
            try {
                val summary = withContext(Dispatchers.IO) { undoExecutor.undo(taskRunId) }
                _actionState.value = HistoryActionState.Done(summary)
            } catch (t: Throwable) {
                _actionState.value = HistoryActionState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun dismissAction() {
        _actionState.value = HistoryActionState.Idle
    }
}
