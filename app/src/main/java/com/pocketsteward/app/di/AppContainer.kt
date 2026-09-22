package com.pocketsteward.app.di

import android.content.Context
import android.media.MediaScannerConnection
import androidx.core.content.ContextCompat
import com.pocketsteward.app.service.ContentIndexWorker
import androidx.work.workDataOf
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import com.pocketsteward.app.service.FileTaskWorker
import androidx.work.ExistingWorkPolicy
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.ai.AgentModel
import com.pocketsteward.app.ai.GeminiNanoAgentModel
import com.pocketsteward.app.content.AndroidPdfContentExtractor
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.content.index.ContentIndexRepository
import com.pocketsteward.app.content.index.ContentSearchDatabase
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.executor.MutationRecovery
import com.pocketsteward.app.executor.PlanExecutor
import com.pocketsteward.app.executor.UndoExecutor
import com.pocketsteward.app.metadata.MetadataEnricher
import com.pocketsteward.app.image.ImageUnderstanding
import com.pocketsteward.app.report.TaskManifestService
import com.pocketsteward.app.scheduled.ScheduledCleanupCoordinator
import com.pocketsteward.app.scan.FileScanner
import com.pocketsteward.app.service.ContentIndexForegroundService
import com.pocketsteward.app.service.FileTaskForegroundService
import com.pocketsteward.app.storage.DirectStorageGateway
import com.pocketsteward.app.storage.SafStorageGateway
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

/**
 * Deliberately manual dependency container rather than Hilt/Dagger. Milestone
 * 0's dependency graph is small enough that a DI framework would add build
 * complexity (another KSP/KAPT processor to version-match) without paying for
 * itself yet; revisit once the graph grows past what this can hold cleanly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val agentModel: AgentModel by lazy { GeminiNanoAgentModel() }
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    val directStorageGateway: StorageGateway by lazy { DirectStorageGateway(appContext) }
    val safStorageGateway: StorageGateway by lazy { SafStorageGateway(appContext) }

    /** The gateway matching whichever access mode onboarding set up. */
    fun gatewayFor(mode: StorageAccessMode): StorageGateway = when (mode) {
        StorageAccessMode.DIRECT -> directStorageGateway
        StorageAccessMode.SAF -> safStorageGateway
    }

    fun fileScanner(mode: StorageAccessMode): FileScanner =
        FileScanner(gatewayFor(mode), database.fileRecordDao(), database.scanCheckpointDao())

    fun contentInspector(mode: StorageAccessMode): ContentInspector =
        ContentInspector(
            gateway = gatewayFor(mode),
            // The PDF extractor consumes an InputStream and stages it in the
            // app cache, so it works for both direct paths and persisted SAF
            // document URIs. Storage access stays behind the gateway.
            pdfExtractor = AndroidPdfContentExtractor(appContext),
        )

    fun contentIndexRepository(mode: StorageAccessMode): ContentIndexRepository =
        ContentIndexRepository(
            dao = ContentSearchDatabase.getInstance(appContext).contentIndexDao(),
            inspector = contentInspector(mode),
        )

    suspend fun contentIndexOverview(): com.pocketsteward.app.content.index.ContentIndexOverview =
        ContentIndexRepository(
            dao = ContentSearchDatabase.getInstance(appContext).contentIndexDao(),
            inspector = contentInspector(StorageAccessMode.DIRECT),
        ).overview()

    fun clearContentIndex(): Boolean {
        return ContentSearchDatabase.delete(appContext)
    }

    fun planExecutor(mode: StorageAccessMode): PlanExecutor =
        PlanExecutor(gatewayFor(mode), database.fileRecordDao(), database.taskRunDao(), database.mutationRecordDao())

    val undoExecutor: UndoExecutor by lazy {
        UndoExecutor(
            database.fileRecordDao(),
            database.taskRunDao(),
            database.mutationRecordDao(),
            ::gatewayFor,
        )
    }

    fun notifyExternalFileCreated(path: String, mimeType: String? = null) {
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(path),
            arrayOf(mimeType),
            null,
        )
    }

    val taskManifestService: TaskManifestService by lazy {
        TaskManifestService(
            taskRunDao = database.taskRunDao(),
            mutationRecordDao = database.mutationRecordDao(),
            onVerifiedExport = { path ->
                notifyExternalFileCreated(
                    path,
                    if (path.endsWith(".json", ignoreCase = true)) "application/json" else "text/markdown",
                )
            },
        )
    }

    val metadataEnricher: MetadataEnricher by lazy { MetadataEnricher(appContext) }
    val imageUnderstanding: ImageUnderstanding by lazy { ImageUnderstanding(appContext) }
    val scheduledCleanupCoordinator: ScheduledCleanupCoordinator by lazy { ScheduledCleanupCoordinator(appContext) }

    val mutationRecovery: MutationRecovery by lazy {
        MutationRecovery(database.mutationRecordDao(), database.taskRunDao(), ::gatewayFor)
    }

    fun startForegroundTask(taskRunId: Long) {
        try {
            ContextCompat.startForegroundService(
                appContext,
                FileTaskForegroundService.runIntent(appContext, taskRunId),
            )
        } catch (_: IllegalStateException) {
            enqueueFileTaskFallback(taskRunId)
        }
    }

    private fun enqueueFileTaskFallback(taskRunId: Long) {
        val request = OneTimeWorkRequestBuilder<FileTaskWorker>()
            .setInputData(workDataOf(FileTaskWorker.KEY_TASK_RUN_ID to taskRunId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(FileTaskWorker.WORK_TAG)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            FileTaskWorker.uniqueName(taskRunId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun pauseForegroundTask() {
        // Record explicit user intent durably. A system kill leaves RUNNING so
        // startup recovery may continue it; an explicit Pause becomes
        // CANCELLED and StartupRecoveryPolicy will not resurrect it.
        appScope.launch {
            val now = System.currentTimeMillis()
            database.taskRunDao().getRunning().forEach { task ->
                database.taskRunDao().markRunningPaused(
                    id = task.id,
                    completedAt = now,
                    summary = "Paused by user · progress preserved in the mutation journal",
                )
            }
        }

        WorkManager.getInstance(appContext).cancelAllWorkByTag(FileTaskWorker.WORK_TAG)
        val pauseDelivered = runCatching {
            appContext.startService(FileTaskForegroundService.pauseIntent(appContext))
        }.getOrNull() != null
        if (!pauseDelivered) {
            // If Android refuses a service command from the current lifecycle
            // state, stop the already-running service. Its cancellation path
            // leaves any in-flight PENDING journal row for conservative
            // recovery and never replays a committed sequence.
            appContext.stopService(
                android.content.Intent(appContext, FileTaskForegroundService::class.java),
            )
        }
    }

    fun startContentIndexing(sourceRoots: List<String>, mode: StorageAccessMode) {
        val roots = sourceRoots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        if (roots.isEmpty()) return

        try {
            ContextCompat.startForegroundService(
                appContext,
                ContentIndexForegroundService.runIntent(appContext, roots, mode),
            )
        } catch (_: IllegalStateException) {
            enqueueContentIndexFallback(roots, mode)
        }
    }

    private fun enqueueContentIndexFallback(roots: List<String>, mode: StorageAccessMode) {
        val request = OneTimeWorkRequestBuilder<ContentIndexWorker>()
            .setInputData(
                workDataOf(
                    ContentIndexWorker.KEY_ROOTS to roots.toTypedArray(),
                    ContentIndexWorker.KEY_MODE to mode.name,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(ContentIndexWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueue(request)
    }

    fun pauseContentIndexing() {
        WorkManager.getInstance(appContext).cancelAllWorkByTag(ContentIndexWorker.WORK_TAG)
        val pauseDelivered = runCatching {
            appContext.startService(ContentIndexForegroundService.pauseIntent(appContext))
        }.getOrNull() != null
        if (!pauseDelivered) {
            appContext.stopService(
                android.content.Intent(appContext, ContentIndexForegroundService::class.java),
            )
        }
    }
}
