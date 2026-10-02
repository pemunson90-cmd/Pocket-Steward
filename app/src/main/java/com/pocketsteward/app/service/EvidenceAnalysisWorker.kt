package com.pocketsteward.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pocketsteward.app.MainActivity
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.R
import com.pocketsteward.app.content.ContentInspectionBudget
import com.pocketsteward.app.content.index.ContentIndexCandidate
import com.pocketsteward.app.content.index.IndexedExtractionStatus
import com.pocketsteward.app.evidence.analysis.*
import com.pocketsteward.app.image.ImageReviewBatch
import com.pocketsteward.app.image.ImageReviewBudget
import com.pocketsteward.app.storage.DirectProtection
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.parseFileRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock

/** Only reads approved-intake snapshots and writes derived evidence; it has no executor. */
class EvidenceAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as PocketStewardApplication).container
    private val coordinator get() = container.evidenceAnalysis

    override suspend fun doWork(): Result = coordinator.execution.withLock { executeJob() }

    private suspend fun executeJob(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val request = try { coordinator.store.request(id) } catch (_: Exception) {
            coordinator.reportFailure(id)
            return@withContext Result.failure()
        }
        val initial = try { coordinator.store.progress(id).also { it.validate(request) } } catch (_: Exception) {
            coordinator.reportFailure(id)
            return@withContext Result.failure()
        }
        if (initial.pauseRequested || initial.status == EvidenceAnalysisStatus.COMPLETED) return@withContext Result.success()
        var foreground = true
        try { setForeground(foregroundInfo(initial)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalStateException) { foreground = false }
        catch (_: SecurityException) { foreground = false }
        val started = SystemClock.elapsedRealtime()
        val folderGuard = EvidenceAnalysisFolderGuard(request.folders) { ref ->
            val refusal = if (request.mode == StorageAccessMode.DIRECT) {
                @Suppress("DEPRECATION") val root = Environment.getExternalStorageDirectory().absolutePath
                DirectProtection.refusal(root, ref)
            } else {
                require(com.pocketsteward.app.storage.SafScopeAccess.contains(applicationContext, requireNotNull(request.grant), ref)) { "Folder is outside the current grant." }
                com.pocketsteward.app.storage.SafProtection(applicationContext).refusal(Uri.parse(ref))
            }
            require(refusal == null) { refusal ?: "Folder protection is unavailable." }
            com.pocketsteward.app.plan.SourcePreconditions.capture(container.gatewayFor(request.mode), parseFileRef(ref))
        }
        var lastNotification = 0L
        suspend fun hasAccess(): Boolean {
            val access = container.settingsRepository.storageAccessState.first()
            val privacy = container.settingsRepository.privacySettings.first()
            return access.mode == request.mode && (request.mode != StorageAccessMode.SAF || access.safTreeUri == request.grant) &&
                (!request.automatic || container.settingsRepository.librarySettings.first().evidenceAnalysisWhileCharging) &&
                (!request.images || privacy.imageAnalysisEnabled) && (!request.content || privacy.contentInspectionEnabled)
        }
        try {
            val finished = EvidenceAnalysisRunner(coordinator.store).run(request,
                allowed = { !isStopped && SystemClock.elapsedRealtime() - started < 8 * 60_000 && hasAccess() },
                inspect = { source ->
                    try {
                        val refusal = withTimeout(5 * 60_000L) { folderGuard.refusal(source) }
                        refusal ?: withTimeout(90_000) { inspect(request, source) }
                    } catch (timeout: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        // An SDK task may still own its bitmap after coroutine timeout. Do not
                        // keep admitting new images while a potentially stuck task remains.
                        coordinator.publish(coordinator.store.update(request.id) {
                            it.copy(pauseRequested = true, detail = "A file or folder inspection timed out. Analysis is paused; resume after checking device resources.")
                        })
                        EvidenceAnalysisOutcome.UNAVAILABLE
                    }
                },
                onProgress = { progress ->
                    coordinator.publish(progress)
                    val now = SystemClock.elapsedRealtime()
                    if (foreground && now - lastNotification > 1000) {
                        lastNotification = now
                        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, foregroundInfo(progress).notification)
                    }
                },
            )
            if (finished.status == EvidenceAnalysisStatus.PAUSED && !finished.pauseRequested && !isStopped && hasAccess()) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (BackgroundWorkPolicy.shouldRetry(runAttemptCount)) Result.retry() else Result.failure()
        }
    }

    private suspend fun inspect(request: EvidenceAnalysisRequest, source: EvidenceAnalysisSource): EvidenceAnalysisOutcome {
        val record = source.record
        val gateway = container.gatewayFor(request.mode)
        if (request.mode == StorageAccessMode.DIRECT) {
            @Suppress("DEPRECATION") val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            if (DirectProtection.refusal(storageRoot, record.stableRef) != null) return EvidenceAnalysisOutcome.UNAVAILABLE
        } else {
            if (!com.pocketsteward.app.storage.SafScopeAccess.contains(applicationContext, requireNotNull(request.grant), record.stableRef) ||
                com.pocketsteward.app.storage.SafProtection(applicationContext).refusal(Uri.parse(record.stableRef)) != null) {
                return EvidenceAnalysisOutcome.UNAVAILABLE
            }
        }
        val metadata = try { gateway.stat(parseFileRef(record.stableRef)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return EvidenceAnalysisOutcome.UNAVAILABLE }
        if (metadata.isDirectory || metadata.displayName != record.displayName || metadata.sizeBytes != record.sizeBytes || metadata.modifiedAtEpochMs != record.modifiedAt) return EvidenceAnalysisOutcome.CHANGED
        if (request.images && record.extension.lowercase() in ImageReviewBatch.EXTENSIONS) {
            val result = ImageReviewBatch(container.imageUnderstanding, ImageReviewBudget(1, if (request.content) 1 else 0))
                .inspect(listOf(record), request.content, request.retryUnavailable)
            val insight = result.evidence[record.stableRef] ?: return EvidenceAnalysisOutcome.UNAVAILABLE
            return when {
                result.coverage.progressNotSaved > 0 || request.content && !insight.textInspectionComplete -> EvidenceAnalysisOutcome.PARTIAL
                result.coverage.cached > 0 -> EvidenceAnalysisOutcome.REUSED
                else -> EvidenceAnalysisOutcome.ANALYZED
            }
        }
        if (request.content) {
            val result = container.contentIndexRepository(request.mode).ensureDocument(ContentIndexCandidate(record, source.sourceRoot), ContentInspectionBudget.FULL)
            return when {
                result.document.extractionStatus != IndexedExtractionStatus.INDEXED.name -> EvidenceAnalysisOutcome.UNAVAILABLE
                !result.document.coverageComplete -> EvidenceAnalysisOutcome.PARTIAL
                result.reused -> EvidenceAnalysisOutcome.REUSED
                else -> EvidenceAnalysisOutcome.ANALYZED
            }
        }
        return EvidenceAnalysisOutcome.UNAVAILABLE
    }

    private fun foregroundInfo(progress: EvidenceAnalysisProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Local evidence analysis", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 31, Intent(applicationContext, MainActivity::class.java).apply {
            data = Uri.parse("pocketsteward://explore"); flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Analyzing library evidence locally").setContentText(progress.summary)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(progress.total, progress.processed, false).build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        const val KEY_JOB_ID = "analysis_job_id"
        const val WORK_TAG = "pocket-steward-evidence-analysis"
        private const val CHANNEL = "pocket_steward_evidence_analysis"
        private const val NOTIFICATION_ID = 1401
    }
}
