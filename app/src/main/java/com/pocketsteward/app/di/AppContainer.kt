package com.pocketsteward.app.di

import android.content.Context
import android.media.MediaScannerConnection
import androidx.core.content.ContextCompat
import com.pocketsteward.app.service.BackgroundWorkPolicy
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
import com.pocketsteward.app.diagnostics.RuntimeDiagnostics
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
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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

    /** Application context for UI-side helpers that need one (never an Activity). */
    val appContextForUi: Context get() = appContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val evidenceAnalysis: com.pocketsteward.app.evidence.analysis.EvidenceAnalysisCoordinator by lazy {
        com.pocketsteward.app.evidence.analysis.EvidenceAnalysisCoordinator(appContext, appScope)
    }
    val agentModel: AgentModel by lazy { GeminiNanoAgentModel() }
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    val inboxObservation: com.pocketsteward.app.library.InboxObservation by lazy {
        com.pocketsteward.app.library.InboxObservation(appContext, settingsRepository, database.taskRunDao(), appScope,
            onEvidenceChange = { paths -> noticeEvidenceChanges(paths) })
    }

    /** One walker per scan root, shared by the background library and manual scans. */
    val scanLocks: com.pocketsteward.app.library.ScanLocks by lazy { com.pocketsteward.app.library.ScanLocks() }

    val library: com.pocketsteward.app.library.LibraryRepository by lazy {
        com.pocketsteward.app.library.LibraryRepository(
            gatewayFor = ::gatewayFor,
            scannerFor = ::fileScanner,
            fileRecordDao = database.fileRecordDao(),
            checkpointDao = database.scanCheckpointDao(),
            locks = scanLocks,
            readLastCompleted = { settingsRepository.libraryLastCompleted() },
            writeLastCompleted = { root, at -> settingsRepository.setLibraryLastCompleted(root, at) },
        )
    }

    val projectKnowledge: com.pocketsteward.app.projects.ProjectKnowledge by lazy {
        com.pocketsteward.app.projects.ProjectKnowledge(database.fileRecordDao(), directStorageGateway,
            com.pocketsteward.app.projects.ProjectDiscoveryStore(java.io.File(appContext.noBackupFilesDir, "project-discovery-pages")))
    }

    val inventoryInvalidations by lazy { com.pocketsteward.app.library.DirectoryInvalidationStore(java.io.File(appContext.noBackupFilesDir, "inventory-invalidations.json")) }
    val observedEvidence by lazy { com.pocketsteward.app.evidence.ObservedEvidenceStore(java.io.File(appContext.noBackupFilesDir, "observed-evidence-revisions")) }
    private val evidenceFlushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun evidenceRevision(ref: String): String = observedEvidence.revision(
        if (ref.startsWith('/')) java.io.File(ref).canonicalPath else ref)
    private fun noticeEvidenceChanges(paths: Set<String>, full: Boolean = false) {
        val canonical = runCatching { paths.mapTo(hashSetOf()) { if (it.startsWith('/')) java.io.File(it).canonicalPath else it } }
        observedEvidence.observe(canonical.getOrDefault(emptySet()), full || canonical.isFailure)
        if (!evidenceFlushScheduled.compareAndSet(false, true)) return
        appScope.launch {
            try {
                do { kotlinx.coroutines.delay(250) } while (observedEvidence.flush() && observedEvidence.hasPending())
            } finally {
                evidenceFlushScheduled.set(false)
                if (observedEvidence.hasPending()) {
                    // A failed save does not spin; live cache reads fail closed and retry the save.
                    if (observedEvidence.flush()) noticeEvidenceChanges(emptySet())
                }
            }
        }
    }

    private suspend fun queueMutationInventory(operations: List<com.pocketsteward.app.plan.PlannedOperation>, undo: Boolean = false) {
        val trashSources = operations.mapNotNull { operation -> when (operation) {
            is com.pocketsteward.app.plan.PlannedOperation.Trash -> operation.source.takeUnless { undo }
            is com.pocketsteward.app.plan.PlannedOperation.Copy -> operation.destination.takeIf { undo }
            is com.pocketsteward.app.plan.PlannedOperation.WriteTextFile -> if (undo) operation.parent.child(operation.name) else null
            else -> null
        } }.distinct()
        val trashDestinations = mutableListOf<com.pocketsteward.app.storage.FileRef>()
        var full = operations.isEmpty()
        for (source in trashSources) {
            try { trashDestinations += directStorageGateway.trashDestination(source) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { full = true }
        }
        val paths = com.pocketsteward.app.library.MutationInventoryPolicy.directories(operations, trashDestinations)
        val evidenceChanges = com.pocketsteward.app.library.MutationInventoryPolicy.evidenceChanges(operations, trashDestinations)
        noticeEvidenceChanges(evidenceChanges.refs, evidenceChanges.full)
        observedEvidence.flush()
        val saved = if (full || paths.isEmpty()) inventoryInvalidations.markFull() else inventoryInvalidations.mark(paths)
        com.pocketsteward.app.service.LibraryRefreshWorker.afterMutation(appContext, fullFallback = !saved)
    }

    val directStorageGateway: StorageGateway by lazy { DirectStorageGateway(appContext) }
    val safStorageGateway: StorageGateway by lazy { SafStorageGateway(appContext) }

    /** The gateway matching whichever access mode onboarding set up. */
    fun gatewayFor(mode: StorageAccessMode): StorageGateway = when (mode) {
        StorageAccessMode.DIRECT -> directStorageGateway
        StorageAccessMode.SAF -> safStorageGateway
    }

    fun fileScanner(mode: StorageAccessMode): FileScanner =
        FileScanner(gatewayFor(mode), database.fileRecordDao(), database.scanCheckpointDao()) { ref ->
            mode == StorageAccessMode.DIRECT && com.pocketsteward.app.scan.ScanReadPolicy.excludedPrivateDirectory(
                android.os.Environment.getExternalStorageDirectory().absolutePath, ref)
        }

    fun contentInspector(mode: StorageAccessMode): ContentInspector =
        ContentInspector(
            gateway = gatewayFor(mode),
            // The PDF extractor consumes an InputStream and stages it in the
            // app cache, so it works for both direct paths and persisted SAF
            // document URIs. Storage access stays behind the gateway.
            pdfExtractor = AndroidPdfContentExtractor(appContext),
            observedRevision = ::evidenceRevision,
        )

    fun contentIndexRepository(mode: StorageAccessMode): ContentIndexRepository =
        ContentIndexRepository(
            dao = ContentSearchDatabase.getInstance(appContext).contentIndexDao(),
            inspector = contentInspector(mode),
            inspectionAllowed = { settingsRepository.privacySettings.first().contentInspectionEnabled },
        )

    suspend fun verifyAskCandidates(rows: List<com.pocketsteward.app.content.ask.AskCandidateRow>): List<com.pocketsteward.app.content.ask.AskCandidateRow> {
        if (!settingsRepository.privacySettings.first().contentInspectionEnabled) return emptyList()
        val access = settingsRepository.storageAccessState.first()
        val mode = access.mode ?: return emptyList()
        val prefix = when (mode) {
            StorageAccessMode.DIRECT -> library.root(access)?.rawValue()?.trimEnd('/')?.plus("/") ?: return emptyList()
            StorageAccessMode.SAF -> access.safTreeUri?.substringBefore("/document/")?.trimEnd('/')?.plus("/document/") ?: return emptyList()
        }
        val scoped = rows.filter { it.stableRef.startsWith(prefix) }
        val inspector = contentInspector(mode)
        val current = com.pocketsteward.app.content.index.ContentEvidenceVerifier(observeFingerprint = { snapshot ->
            if (!settingsRepository.privacySettings.first().contentInspectionEnabled) throw kotlinx.coroutines.CancellationException("Content inspection is off.")
            inspector.evidenceFingerprint(snapshot.stableRef, requireNotNull(snapshot.size))
        }, observe = inspector::observeMetadata)
            .currentRefs(scoped.map { row ->
                com.pocketsteward.app.content.index.ContentEvidenceSnapshot(row.stableRef, row.displayName, row.sizeBytes, row.modifiedAt, row.quickFingerprint)
            })
        return if (settingsRepository.privacySettings.first().contentInspectionEnabled) scoped.filter { it.stableRef in current } else emptyList()
    }

    suspend fun contentIndexOverview(): com.pocketsteward.app.content.index.ContentIndexOverview =
        ContentIndexRepository(
            dao = ContentSearchDatabase.getInstance(appContext).contentIndexDao(),
            inspector = contentInspector(StorageAccessMode.DIRECT),
        ).overview()

    suspend fun clearContentIndex(): Boolean {
        evidenceAnalysis.pause()
        pauseContentIndexing()
        com.pocketsteward.app.content.index.ContentIndexCoordination.Shared.clear {
            ContentSearchDatabase.getInstance(appContext).contentIndexDao().clearAll()
        }
        return true
    }

    private val mutationRunnerGate = com.pocketsteward.app.executor.MutationRunnerGate()

    fun planExecutor(mode: StorageAccessMode): PlanExecutor =
        PlanExecutor(gatewayFor(mode), database.fileRecordDao(), database.taskRunDao(), database.mutationRecordDao(),
            onPlanActivated = { operations -> if (mode == StorageAccessMode.DIRECT) queueMutationInventory(operations) },
            runnerGate = mutationRunnerGate,
            beforeRun = { mutationRecovery.recoverForRunner() })

    val undoExecutor: UndoExecutor by lazy {
        UndoExecutor(
            database.fileRecordDao(),
            database.taskRunDao(),
            database.mutationRecordDao(),
            ::gatewayFor,
            onUndoActivated = { mode, operations -> if (mode == StorageAccessMode.DIRECT) queueMutationInventory(operations, undo = true) },
            runnerGate = mutationRunnerGate,
            beforeRun = { mutationRecovery.recoverForRunner() },
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
            gatewayFor = ::gatewayFor,
            onVerifiedExport = { path ->
                notifyExternalFileCreated(
                    path,
                    if (path.endsWith(".json", ignoreCase = true)) "application/json" else "text/markdown",
                )
            },
        )
    }

    val metadataEnricher: MetadataEnricher by lazy { MetadataEnricher(appContext, ::evidenceRevision) }
    val imageUnderstanding: ImageUnderstanding by lazy { ImageUnderstanding(appContext,
        readFingerprint = { record -> contentInspector(if (record.stableRef.startsWith("content://")) StorageAccessMode.SAF else StorageAccessMode.DIRECT).evidenceFingerprint(record) },
        observeMetadata = { ref -> contentInspector(if (ref.startsWith("content://")) StorageAccessMode.SAF else StorageAccessMode.DIRECT).observeMetadata(ref) },
    ) }
    val fileEvidenceInspector by lazy { com.pocketsteward.app.evidence.createFileEvidenceInspector(this) }
    val scheduledCleanupCoordinator: ScheduledCleanupCoordinator by lazy { ScheduledCleanupCoordinator(appContext) }
    val runtimeDiagnostics: RuntimeDiagnostics by lazy {
        RuntimeDiagnostics(
            context = appContext,
            database = database,
            contentIndexOverview = ::contentIndexOverview,
        )
    }

    val mutationRecovery: MutationRecovery by lazy {
        MutationRecovery(database.mutationRecordDao(), database.taskRunDao(), mutationRunnerGate, ::gatewayFor)
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

    internal fun enqueueFileTaskFallback(taskRunId: Long) {
        val request = OneTimeWorkRequestBuilder<FileTaskWorker>()
            .setInputData(workDataOf(FileTaskWorker.KEY_TASK_RUN_ID to taskRunId))
            .setConstraints(BackgroundWorkPolicy.fileMutationConstraints())
            .addTag(FileTaskWorker.WORK_TAG)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            FileTaskWorker.uniqueName(taskRunId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun <T> withLibraryRefresh(block: suspend () -> T): T = mutationRunnerGate.run(block)

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

    internal fun enqueueContentIndexFallback(roots: List<String>, mode: StorageAccessMode) {
        val request = OneTimeWorkRequestBuilder<ContentIndexWorker>()
            .setInputData(
                workDataOf(
                    ContentIndexWorker.KEY_ROOTS to roots.toTypedArray(),
                    ContentIndexWorker.KEY_MODE to mode.name,
                ),
            )
            .setConstraints(BackgroundWorkPolicy.contentIndexConstraints())
            .addTag(ContentIndexWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            ContentIndexWorker.uniqueName(mode, roots),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Whole-library content indexing, waiting for the charger. Kept separate
     * from [startContentIndexing], which runs a folder the user is searching
     * right now in the foreground.
     */
    fun enqueueChargingContentIndex(root: String, mode: StorageAccessMode) {
        val roots = listOf(root.trimEnd('/'))
        val request = OneTimeWorkRequestBuilder<ContentIndexWorker>()
            .setInputData(
                workDataOf(
                    ContentIndexWorker.KEY_ROOTS to roots.toTypedArray(),
                    ContentIndexWorker.KEY_MODE to mode.name,
                ),
            )
            .setConstraints(BackgroundWorkPolicy.libraryContentIndexConstraints())
            .addTag(ContentIndexWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            ContentIndexWorker.uniqueName(mode, roots),
            ExistingWorkPolicy.KEEP,
            request,
        )
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
