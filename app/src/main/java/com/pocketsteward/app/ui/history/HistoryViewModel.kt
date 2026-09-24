package com.pocketsteward.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskJournalProgress
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.db.UndoState
import com.pocketsteward.app.executor.UndoExecutor
import com.pocketsteward.app.executor.UndoSummary
import com.pocketsteward.app.report.ExportResult
import com.pocketsteward.app.report.ManifestFormat
import com.pocketsteward.app.report.TaskManifestDocument
import com.pocketsteward.app.report.TaskManifestService
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Above this many reversible operations, Undo asks first. The 2026-09-17
 * Smart cleanup was 4,834 operations and its Undo was a single unguarded
 * tap; undoing that by accident is worse than the organize it reverses,
 * because the files end up somewhere nobody chose.
 */
const val UNDO_CONFIRM_THRESHOLD: Int = 25

sealed interface HistoryActionState {
    data object Idle : HistoryActionState

    /** A large undo waiting on an explicit yes. Nothing has been touched yet. */
    data class ConfirmUndo(val task: TaskRun, val operationCount: Int) : HistoryActionState

    data class Undoing(
        val taskRunId: Long,
        val completed: Int = 0,
        val total: Int = 0,
    ) : HistoryActionState

    data class BackgroundStarted(val message: String) : HistoryActionState

    data class Done(val summary: UndoSummary) : HistoryActionState
    data class Manifest(val document: TaskManifestDocument, val exportedTo: String? = null) : HistoryActionState
    data class Error(val message: String) : HistoryActionState
}

class HistoryViewModel(
    private val taskRunDao: TaskRunDao,
    private val undoExecutor: UndoExecutor,
    private val manifestService: TaskManifestService,
    private val mutationRecordDao: MutationRecordDao,
    private val gatewayFor: (StorageAccessMode) -> StorageGateway,
    private val startForegroundTask: (Long) -> Unit,
    private val pauseForegroundTask: () -> Unit,
) : ViewModel() {
    val tasks: StateFlow<List<TaskRun>> = taskRunDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val taskProgress: StateFlow<Map<Long, TaskJournalProgress>> =
        mutationRecordDao.observeTaskProgress()
            .map { rows -> rows.associateBy(TaskJournalProgress::taskRunId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _actionState = MutableStateFlow<HistoryActionState>(HistoryActionState.Idle)
    val actionState: StateFlow<HistoryActionState> = _actionState

    fun resumeTask(task: TaskRun) {
        viewModelScope.launch {
            try {
                val otherRunning = withContext(Dispatchers.IO) {
                    taskRunDao.getRunning().firstOrNull { it.id != task.id }
                }
                if (otherRunning != null) {
                    _actionState.value = HistoryActionState.Error(
                        "Task #${otherRunning.id} is already running. Pause or finish it before continuing another task.",
                    )
                    return@launch
                }
                startForegroundTask(task.id)
                _actionState.value = HistoryActionState.BackgroundStarted(
                    "Task #${task.id} is continuing with the durable background runner. Progress is shown here and in the notification.",
                )
            } catch (t: Throwable) {
                _actionState.value = HistoryActionState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun pauseTask() {
        try {
            pauseForegroundTask()
            _actionState.value = HistoryActionState.BackgroundStarted(
                "Pause requested. Pocket Steward will finish the current file operation, save progress, then stop.",
            )
        } catch (t: Throwable) {
            _actionState.value = HistoryActionState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Asks first when the run is large, otherwise goes straight to it. */
    fun requestUndo(task: TaskRun) {
        viewModelScope.launch {
            val count = try {
                withContext(Dispatchers.IO) { reversibleCount(task.id) }
            } catch (t: Throwable) {
                _actionState.value = HistoryActionState.Error(t.message ?: t.javaClass.simpleName)
                return@launch
            }
            if (count > UNDO_CONFIRM_THRESHOLD) {
                _actionState.value = HistoryActionState.ConfirmUndo(task, count)
            } else {
                runUndo(task.id)
            }
        }
    }

    /**
     * What Undo would actually reverse, not how many operations the run had.
     * A run whose journal rows are mostly already undone shouldn't prompt.
     */
    private suspend fun reversibleCount(taskRunId: Long): Int =
        mutationRecordDao.getForTaskRun(taskRunId)
            .count {
                it.status == MutationStatus.COMMITTED &&
                    (it.undoState == UndoState.AVAILABLE || it.undoState == UndoState.BLOCKED)
            }

    fun confirmUndo(taskRunId: Long) {
        viewModelScope.launch { runUndo(taskRunId) }
    }

    private suspend fun runUndo(taskRunId: Long) {
        _actionState.value = HistoryActionState.Undoing(taskRunId)
        try {
            val summary = withContext(Dispatchers.IO) {
                undoExecutor.undo(taskRunId) { completed, total ->
                    _actionState.value = HistoryActionState.Undoing(taskRunId, completed, total)
                }
            }
            _actionState.value = HistoryActionState.Done(summary)
        } catch (t: Throwable) {
            _actionState.value = HistoryActionState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    fun showManifest(taskRunId: Long) {
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) { manifestService.build(taskRunId) }
                _actionState.value = if (document == null) {
                    HistoryActionState.Error("That task is no longer in the journal.")
                } else {
                    HistoryActionState.Manifest(document)
                }
            } catch (t: Throwable) {
                _actionState.value = HistoryActionState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun exportManifest(
        document: TaskManifestDocument,
        format: ManifestFormat = ManifestFormat.MARKDOWN,
    ) {
        viewModelScope.launch {
            try {
                val task = withContext(Dispatchers.IO) { taskRunDao.getById(document.taskRunId) }
                if (task == null) {
                    _actionState.value = HistoryActionState.Error("That task is no longer in the journal.")
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    manifestService.export(
                        document = document,
                        gateway = gatewayFor(task.storageAccessMode),
                        scopeRootRef = task.scopeRootRef,
                        format = format,
                    )
                }
                _actionState.value = when (result) {
                    is ExportResult.Written -> HistoryActionState.Manifest(document, exportedTo = result.path)
                    is ExportResult.Failed -> HistoryActionState.Error("Export failed: ${result.reason}")
                }
            } catch (t: Throwable) {
                _actionState.value = HistoryActionState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun dismissAction() {
        _actionState.value = HistoryActionState.Idle
    }
}
