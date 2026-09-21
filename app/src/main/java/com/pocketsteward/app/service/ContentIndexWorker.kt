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

/**
 * Background-safe fallback for content indexing when Android refuses a new
 * foreground-service launch because the app is no longer in an allowed
 * foreground-start state.
 *
 * The same durable index job/cursor is used as ContentIndexForegroundService,
 * so switching between the two runners cannot duplicate or lose progress.
 */
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

        val repository = container.contentIndexRepository(StorageAccessMode.DIRECT)
        var activeRoot: String? = null

        return try {
            for (root in roots) {
                if (isStopped) break
                activeRoot = root

                val records = container.database.fileRecordDao()
                    .getFilesUnderScopeRoot(root)
                val candidates = records.map { record ->
                    ContentIndexCandidate(record = record, sourceRoot = root)
                }

                val finalJob = repository.refreshRootResumable(
                    candidates = candidates,
                    sourceRoot = root,
                    shouldPause = { isStopped },
                )

                if (finalJob.status == ContentIndexJobStatus.PAUSED.name || isStopped) {
                    break
                }
            }

            activeRoot = null
            Result.success()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                activeRoot?.let {
                    repository.markPaused(
                        it,
                        "Background indexing was interrupted and can resume.",
                    )
                }
            }
            throw cancel
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                activeRoot?.let {
                    repository.markPaused(
                        it,
                        t.message ?: "Background indexing stopped.",
                    )
                }
            }
            Result.retry()
        }
    }

    companion object {
        const val WORK_TAG = "pocket-steward-content-index"
        const val KEY_ROOTS = "source_roots"
    }
}
