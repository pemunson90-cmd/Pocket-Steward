package com.pocketsteward.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.db.MutationRecord
import com.pocketsteward.app.data.db.MutationRecordDao
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One file sitting in Trash, as the screen needs it: where it came from, where
 * it is now, when it was trashed, and the journal entry that can put it back.
 */
data class TrashedFile(
    val mutationId: Long,
    val displayName: String,
    val originalPath: String,
    val trashedAt: Long?,
)

sealed interface RestoreState {
    data object Idle : RestoreState
    data class Restoring(val mutationId: Long) : RestoreState
    data class Failed(val reason: String) : RestoreState
}

class TrashViewModel(
    mutationRecordDao: MutationRecordDao,
    private val container: AppContainer,
) : ViewModel() {

    val trashed: StateFlow<List<TrashedFile>> = mutationRecordDao.observeTrashed()
        .map { records -> records.map { it.toTrashedFile() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState

    /**
     * Puts one file back where it came from, through the same undo machinery
     * a whole-task undo uses. If something now occupies the original path the
     * inverse blocks rather than overwriting, and that reason surfaces here.
     */
    fun restore(mutationId: Long) {
        viewModelScope.launch {
            _restoreState.value = RestoreState.Restoring(mutationId)
            try {
                // No need to resolve a storage mode here: undoSingleMutation
                // reads it off the TaskRun that made the change, so a restore
                // always uses the same gateway that did the trashing.
                when (val result = withContext(Dispatchers.IO) { container.undoExecutor.undoSingleMutation(mutationId) }) {
                    is MutationResult.Success -> _restoreState.value = RestoreState.Idle
                    is MutationResult.Failure -> _restoreState.value = RestoreState.Failed(result.reason)
                }
            } catch (t: Throwable) {
                _restoreState.value = RestoreState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun dismissError() {
        _restoreState.value = RestoreState.Idle
    }
}

private fun MutationRecord.toTrashedFile(): TrashedFile {
    val original = FileRefJournalCodec.decode(sourceBefore).rawValue()
    return TrashedFile(
        mutationId = id,
        displayName = original.substringAfterLast('/'),
        originalPath = original,
        trashedAt = executedAt,
    )
}
