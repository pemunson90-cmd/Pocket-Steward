package com.pocketsteward.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.content.index.ContentIndexCandidate
import com.pocketsteward.app.content.index.ContentIndexJobStatus
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ContentIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container
        get() = (applicationContext as PocketStewardApplication).container

    override suspend fun doWork(): Result {
        val roots = inputData.getStringArray(KEY_ROOTS)
            ?.map { it.trimEnd('/') }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        if (roots.isEmpty()) return Result.success()

        val mode = inputData.getString(KEY_MODE)
            ?.let { runCatching { StorageAccessMode.valueOf(it) }.getOrNull() }
            ?: StorageAccessMode.DIRECT
        val repository = container.contentIndexRepository(mode)
        var activeRoot: String? = null

        return try {
            for (root in roots) {
                if (isStopped) break
                activeRoot = root
                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root)
                val candidates = records.map { record ->
                    ContentIndexCandidate(record = record, sourceRoot = root)
                }
                val finalJob = repository.refreshRootResumable(
                    candidates = candidates,
                    sourceRoot = root,
                    shouldPause = { isStopped },
                )
                if (finalJob.status == ContentIndexJobStatus.PAUSED.name || isStopped) break
            }
            activeRoot = null
            Result.success()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                activeRoot?.let {
                    repository.markPaused(it, "Background indexing was interrupted and can resume.")
                }
            }
            throw cancel
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                activeRoot?.let {
                    repository.markPaused(it, t.message ?: "Background indexing stopped.")
                }
            }
            Result.retry()
        }
    }

    companion object {
        const val WORK_TAG = "pocket-steward-content-index"
        const val KEY_ROOTS = "source_roots"
        const val KEY_MODE = "storage_mode"
    }
}
