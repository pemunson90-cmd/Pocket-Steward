package com.pocketsteward.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pocketsteward.app.PocketStewardApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Background-safe fallback for an already-approved durable file task.
 *
 * This worker never plans or edits a plan. It receives only a TaskRun id,
 * recovers any interrupted journal row, then resumes the exact durable plan
 * already approved by the user.
 */
class FileTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val container
        get() = (applicationContext as PocketStewardApplication).container

    override suspend fun doWork(): Result {
        val taskRunId = inputData.getLong(KEY_TASK_RUN_ID, -1L)
        if (taskRunId <= 0L) return Result.failure()

        return try {
            container.mutationRecovery.recoverAll()
            val task = container.database.taskRunDao().getById(taskRunId)
                ?: return Result.failure()
            val result = container.planExecutor(task.storageAccessMode).resume(
                taskRunId = taskRunId,
                shouldPause = { isStopped },
            )
            if (result.cancelled) Result.success() else Result.success()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                container.mutationRecovery.recoverAll()
                container.database.taskRunDao().markRunningPaused(
                    id = taskRunId,
                    completedAt = System.currentTimeMillis(),
                    summary = "Paused safely after background execution was interrupted. Resume continues from the journal.",
                )
            }
            throw cancel
        } catch (security: SecurityException) {
            withContext(NonCancellable) {
                container.database.taskRunDao().markRunningPaused(
                    id = taskRunId,
                    completedAt = System.currentTimeMillis(),
                    summary = "Paused because storage access is unavailable. Restore access, then Resume from Tasks.",
                )
            }
            Result.failure()
        } catch (_: IllegalArgumentException) {
            // Non-resumable or already-finished task. Nothing should be retried.
            Result.failure()
        } catch (state: IllegalStateException) {
            retryOrPause(taskRunId, state.message ?: "Task runner state error.")
        } catch (t: Throwable) {
            retryOrPause(taskRunId, t.message ?: "Background task runner stopped unexpectedly.")
        }
    }

    private suspend fun retryOrPause(
        taskRunId: Long,
        reason: String,
    ): Result {
        if (BackgroundWorkPolicy.shouldRetry(runAttemptCount)) {
            return Result.retry()
        }

        withContext(NonCancellable) {
            runCatching { container.mutationRecovery.recoverAll() }
            container.database.taskRunDao().markRunningPaused(
                id = taskRunId,
                completedAt = System.currentTimeMillis(),
                summary = "Paused after ${BackgroundWorkPolicy.MAX_TRANSIENT_ATTEMPTS} background attempts. $reason Open Tasks to review and resume.",
            )
        }
        return Result.failure()
    }

    companion object {
        const val KEY_TASK_RUN_ID = "task_run_id"
        const val WORK_TAG = "pocket-steward-file-task"

        fun uniqueName(taskRunId: Long): String =
            "pocket-steward-file-task-$taskRunId"
    }
}
