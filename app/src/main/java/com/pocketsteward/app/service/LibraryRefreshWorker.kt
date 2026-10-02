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
import com.pocketsteward.app.evidence.analysis.analyzeIndexedLibrary
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

    override suspend fun doWork(): Result = container.withLibraryRefresh { refreshLocked() }

    private suspend fun refreshLocked(): Result {
        val settings = container.settingsRepository
        val forced = inputData.getBoolean(KEY_FORCED, false)
        val observed = inputData.getBoolean(KEY_OBSERVED, false)
        val mutation = inputData.getBoolean(KEY_MUTATION, false)
        val library = settings.librarySettings.first()
        if (!com.pocketsteward.app.library.InboxObservationPolicy.shouldRefresh(
                library.backgroundRefreshEnabled, observed, forced,
            )) return Result.success()
        // The shared runner gate excludes in-flight writes, including the final step after explicit Pause.
        // Queued approved tasks also take priority over metadata walks.
        if (container.database.taskRunDao().busyCount() > 0) return Result.retry()
        val access = settings.storageAccessState.first()
        val mode = access.mode ?: return Result.success()
        if ((observed || mutation) && mode != com.pocketsteward.app.storage.StorageAccessMode.DIRECT) return Result.success()

        val pending = if (mutation) container.inventoryInvalidations.claim() else null
        if (mutation && pending?.pending != true && !inputData.getBoolean(KEY_FULL_FALLBACK, false)) {
            pending?.let(container.inventoryInvalidations::release)
            return Result.success()
        }
        return try {
            val requested = if (mutation) pending?.directories?.keys?.toList().orEmpty() else inputData.getStringArray(KEY_DIRECTORIES)?.toList().orEmpty()
            val full = inputData.getBoolean(KEY_FULL_FALLBACK, false) || pending?.fullGeneration != null
            val affected = if ((observed || mutation) && !full) container.library.directoryRefreshRoots(access, requested) else null
            val refreshedSlice = affected != null && container.library.refreshDirectories(access, affected, resume = runAttemptCount > 0)
            if (!refreshedSlice && !container.library.refresh(access, forced = forced)) return Result.retry()
            val privacy = settings.privacySettings.first()
            if (library.contentIndexWhileCharging && privacy.contentInspectionEnabled && !library.evidenceAnalysisWhileCharging) {
                if (refreshedSlice) affected.orEmpty().forEach { root -> container.enqueueChargingContentIndex(root, mode) }
                else container.library.root(access)?.let { root -> container.enqueueChargingContentIndex(root.rawValue(), mode) }
            }
            if (library.evidenceAnalysisWhileCharging && (privacy.contentInspectionEnabled || privacy.imageAnalysisEnabled)) {
                container.analyzeIndexedLibrary(automatic = true)
            }
            if (pending != null && !container.inventoryInvalidations.complete(pending)) return Result.retry()
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SecurityException) {
            // Access was withdrawn. Nothing to retry until it comes back.
            Result.failure()
        } catch (_: Throwable) {
            if (BackgroundWorkPolicy.shouldRetry(runAttemptCount)) Result.retry() else Result.failure()
        } finally { pending?.let(container.inventoryInvalidations::release) }
    }

    companion object {
        const val PERIODIC_NAME = "pocket-steward-library-periodic"
        const val NOW_NAME = "pocket-steward-library-now"
        const val OBSERVED_NAME = "pocket-steward-library-observed"
        const val KEY_FORCED = "forced"
        const val KEY_OBSERVED = "observed"
        const val KEY_MUTATION = "own_mutation"
        const val KEY_FULL_FALLBACK = "full_fallback"
        const val MUTATION_NAME = "pocket-steward-library-mutations"
        const val KEY_DIRECTORIES = "affected_directories"

        /** Starts or stops the scheduled refresh to match the setting. */
        fun sync(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC_NAME)
                wm.cancelUniqueWork(OBSERVED_NAME)
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

        /** Read-only metadata refresh after an inbox event; the background switch remains authoritative. */
        fun observeNow(context: Context, directories: List<String> = emptyList()) {
            val bounded = directories.distinct().takeIf { it.size <= com.pocketsteward.app.library.DirectoryRefreshPolicy.MAX_DIRECTORIES && it.sumOf { path -> path.toByteArray(Charsets.UTF_8).size } <= com.pocketsteward.app.library.DirectoryRefreshPolicy.MAX_PATH_BYTES }.orEmpty()
            val request = OneTimeWorkRequestBuilder<LibraryRefreshWorker>()
                .setInputData(workDataOf(KEY_FORCED to true, KEY_OBSERVED to true, KEY_DIRECTORIES to bounded.toTypedArray()))
                .setConstraints(BackgroundWorkPolicy.scheduledSuggestionConstraints())
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(OBSERVED_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Approved mutations keep derived inventory current even when ongoing monitoring is disabled. */
        fun afterMutation(context: Context, fullFallback: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<LibraryRefreshWorker>()
                .setInputData(workDataOf(KEY_FORCED to true, KEY_MUTATION to true, KEY_FULL_FALLBACK to fullFallback))
                .setConstraints(BackgroundWorkPolicy.libraryRefreshNowConstraints())
                .addTag(WORK_TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork(MUTATION_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        const val WORK_TAG = "pocket-steward-library"
    }
}
