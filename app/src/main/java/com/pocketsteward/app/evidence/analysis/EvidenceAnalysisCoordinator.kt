package com.pocketsteward.app.evidence.analysis

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pocketsteward.app.service.BackgroundWorkPolicy
import com.pocketsteward.app.service.EvidenceAnalysisWorker
import com.pocketsteward.app.storage.StorageAccessMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EvidenceAnalysisCoordinator(private val context: Context, scope: CoroutineScope) {
    val store = EvidenceAnalysisStore(File(context.noBackupFilesDir, "library-evidence-jobs-v1"))
    private val admission = Mutex()
    internal val execution = Mutex()
    private val _progress = MutableStateFlow<EvidenceAnalysisProgress?>(null)
    val progress: StateFlow<EvidenceAnalysisProgress?> = _progress
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { scope.launch { withContext(Dispatchers.IO) { admission.withLock {
        runCatching { store.latest() }.onSuccess { saved ->
            _progress.value = saved
            if (saved != null && !saved.pauseRequested && saved.status in setOf(EvidenceAnalysisStatus.QUEUED, EvidenceAnalysisStatus.RUNNING, EvidenceAnalysisStatus.PAUSED)) enqueue(saved.id)
        }.onFailure { _error.value = "Saved evidence analysis could not be read. Start a new analysis from the current review." }
    } } } }

    suspend fun start(sources: List<EvidenceAnalysisSource>, mode: StorageAccessMode, grant: String?, images: Boolean, content: Boolean, retryUnavailable: Boolean = false): String = withContext(Dispatchers.IO) {
        admission.withLock {
            val old = runCatching { store.latest() }.getOrNull()
            require(old == null || old.status !in setOf(EvidenceAnalysisStatus.QUEUED, EvidenceAnalysisStatus.RUNNING)) { "An evidence analysis is already running. Pause it before starting another." }
            val request = EvidenceAnalysisRequest(UUID.randomUUID().toString(), mode, grant, images, content, retryUnavailable, sources.sortedBy { it.record.stableRef })
            store.saveRequest(request)
            _error.value = null
            _progress.value = store.progress(request.id)
            enqueue(request.id)
            request.id
        }
    }
    suspend fun pause() = withContext(Dispatchers.IO) {
        admission.withLock {
            val current = store.latest() ?: return@withLock
            if (current.status == EvidenceAnalysisStatus.COMPLETED) return@withLock
            publish(store.update(current.id) { it.copy(pauseRequested = true, status = EvidenceAnalysisStatus.PAUSED, detail = "Paused by you. Progress is saved.") })
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(current.id))
        }
    }
    suspend fun resume() = withContext(Dispatchers.IO) {
        admission.withLock {
            val current = store.latest() ?: return@withLock
            require(current.status != EvidenceAnalysisStatus.COMPLETED) { "This analysis is already complete." }
            store.progress(current.id).validate(store.request(current.id))
            publish(store.update(current.id) { it.copy(pauseRequested = false, status = EvidenceAnalysisStatus.QUEUED, detail = "Queued to resume from the saved file cursor.") })
            enqueue(current.id)
        }
    }
    fun reportFailure(id: String) {
        runCatching { store.update(id) { it.copy(status = EvidenceAnalysisStatus.FAILED, detail = "Saved analysis could not be read. Start a new analysis from the current review.") } }
            .onSuccess(::publish)
        _error.value = "Saved evidence analysis could not be read. Start a new analysis from the current review."
    }
    fun publish(progress: EvidenceAnalysisProgress) {
        val current = _progress.value
        if (current == null || current.id == progress.id) _progress.value = progress
    }
    private fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<EvidenceAnalysisWorker>()
            .setInputData(workDataOf(EvidenceAnalysisWorker.KEY_JOB_ID to id))
            .setConstraints(BackgroundWorkPolicy.contentIndexConstraints())
            .addTag(EvidenceAnalysisWorker.WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.KEEP, request)
    }
    companion object { fun uniqueName(id: String) = "pocket-steward-library-evidence-$id" }
}
