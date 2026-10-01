package com.pocketsteward.app.ui.scan

import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.plan.DurablePlanCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ScanViewModel.initializeReviewDraftPersistence() {
    viewModelScope.launch {
        val generation = draftGeneration
        try {
            val draft = withContext(Dispatchers.IO) { reviewDraftStore.load() }
            val access = settingsRepository.storageAccessState.first()
            if (draft != null && ReviewDraftPolicy.hasCurrentAccess(draft.preview, access.mode, access.safTreeUri) && generation == draftGeneration && _preview.value == null) {
                // A death between enqueue and deleting the draft leads to Tasks, not another approval.
                val tasks = container.database.taskRunDao().plansAfter(draft.preview.taskHistoryWatermark).mapNotNull { task ->
                    DurablePlanCodec.decodeOrNull(task.planJson)?.let { task.id to it.operations }
                }
                val consumed = ReviewDraftPolicy.wasQueued(draft.preview, tasks)
                if (!consumed) {
                    filingSession = draft.filingSession
                    _preview.value = draft.preview
                    _draftSaveStatus.value = "Restored review · approval still checks current files"
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            _error.value = "Saved review could not be restored. Your files were not changed; rebuild the review."
        }
        combine(_preview, filingSessionRevision) { preview, _ -> preview to filingSession }.collect { (preview, session) ->
            try {
                val saveGeneration = draftGeneration
                _draftSaveStatus.value = if (preview == null) "" else "Saving review…"
                val draft = preview?.let {
                    ReviewDraft(mode = requireNotNull(it.storageMode), preview = it, filingSession = session.takeIf { it != null && preview.filingPresentation != null })
                }
                withContext(Dispatchers.IO) { reviewDraftStore.save(draft) { saveGeneration == draftGeneration && preview == _preview.value } }
                _draftSaveStatus.value = if (preview == null) "" else "Review saved on this device"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _draftSaveStatus.value = "Review is available now, but could not be saved for restart"
            }
        }
    }
}
