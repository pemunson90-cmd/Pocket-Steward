package com.pocketsteward.app.di

import android.content.Context
import androidx.core.content.ContextCompat
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.ai.AgentModel
import com.pocketsteward.app.ai.GeminiNanoAgentModel
import com.pocketsteward.app.content.AndroidPdfContentExtractor
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.executor.MutationRecovery
import com.pocketsteward.app.executor.PlanExecutor
import com.pocketsteward.app.executor.UndoExecutor
import com.pocketsteward.app.report.TaskManifestService
import com.pocketsteward.app.scan.FileScanner
import com.pocketsteward.app.service.FileTaskForegroundService
import com.pocketsteward.app.storage.DirectStorageGateway
import com.pocketsteward.app.storage.SafStorageGateway
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway

/**
 * Deliberately manual dependency container rather than Hilt/Dagger. Milestone
 * 0's dependency graph is small enough that a DI framework would add build
 * complexity (another KSP/KAPT processor to version-match) without paying for
 * itself yet; revisit once the graph grows past what this can hold cleanly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

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
            pdfExtractor = if (mode == StorageAccessMode.DIRECT) AndroidPdfContentExtractor(appContext) else null,
        )

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

    val taskManifestService: TaskManifestService by lazy {
        TaskManifestService(database.taskRunDao(), database.mutationRecordDao())
    }

    val mutationRecovery: MutationRecovery by lazy {
        MutationRecovery(database.mutationRecordDao(), database.taskRunDao(), ::gatewayFor)
    }

    fun startForegroundTask(taskRunId: Long) {
        ContextCompat.startForegroundService(
            appContext,
            FileTaskForegroundService.runIntent(appContext, taskRunId),
        )
    }
}
