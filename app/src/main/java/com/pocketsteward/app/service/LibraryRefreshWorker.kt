package com.pocketsteward.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.library.LibraryPolicy
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Keeps the library current: walks the whole reachable storage (names,
 * sizes, dates only), then, when content indexing is allowed, queues the
 * content index for the same root to run while the phone is charging.
 *
 * Read-only toward storage. If Android stops the worker mid-walk, the
 * scanner's checkpoint keeps the queue and the next run resumes it.
 */
class LibraryRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container
        get() = (applicationContext as PocketStewardApplication).container

    override suspend fun doWork(): Result {
        val settings = container.settingsRepository
        val forced = inputData.getBoolean(KEY_FORCED, false)
        val library = settings.librarySettings.first()
        if (!forced && !library.backgroundRefreshEnabled) return Result.success()
        val access = settings.storageAccessState.first()
        val mode = access.mode ?: return Result.success()

        return try {
            container.library.refresh(access, forced = forced)
            val privacy = settings.privacySettings.first()
            if (library.contentIndexWhileCharging && privacy.contentInspectionEnabled) {
                container.library.root(access)?.let { root ->
                    container.enqueueChargingContentIndex(root.rawValue(), mode)
                }
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SecurityException) {
            // Access was withdrawn. Nothing to retry until it comes back.
            Result.failure()
        } catch (_: Throwable) {
            if (BackgroundWorkPolicy.shouldRetry(runAttemptCount)) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val PERIODIC_NAME = "pocket-steward-library-periodic"
        const val NOW_NAME = "pocket-steward-library-now"
        const val KEY_FORCED = "forced"

        /** Starts or stops the scheduled refresh to match the setting. */
        fun sync(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<LibraryRefreshWorker>(
                LibraryPolicy.PERIODIC_REFRESH_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(BackgroundWorkPolicy.libraryRefreshConstraints())
                .addTag(WORK_TAG)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** A refresh now. Used on app open when the library is stale, and by "Refresh now". */
        fun runNow(context: Context, forced: Boolean) {
            val request = OneTimeWorkRequestBuilder<LibraryRefreshWorker>()
                .setInputData(workDataOf(KEY_FORCED to forced))
                .setConstraints(BackgroundWorkPolicy.libraryRefreshNowConstraints())
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, request)
        }

        const val WORK_TAG = "pocket-steward-library"
    }
}
