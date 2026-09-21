package com.pocketsteward.app.scheduled

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketsteward.app.MainActivity
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.R
import com.pocketsteward.app.rules.RuleEngine
import com.pocketsteward.app.rules.isUncategorized
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class ScheduledCleanupCoordinator(
    private val context: Context,
) {
    fun apply(settings: ScheduledCleanupSettings) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<CleanupSuggestionWorker>(
            settings.intervalHours.coerceAtLeast(1),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK = "pocket-steward-cleanup-suggestions"
    }
}

/**
 * Periodic review generator. It scans metadata and notifies about new obvious
 * candidates; it never constructs or executes a mutation plan unattended.
 */
class CleanupSuggestionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PocketStewardApplication).container
        val settings = container.settingsRepository.scheduledCleanupSettings.first()
        if (!settings.enabled) return Result.success()

        val access = container.settingsRepository.storageAccessState.first()
        if (access.mode != StorageAccessMode.DIRECT) return Result.success()

        val roots = if (settings.roots.isNotEmpty()) {
            settings.roots
        } else {
            container.database.fileRecordDao().getKnownScopeRoots()
        }
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() && File(it).isDirectory }
            .distinct()

        if (roots.isEmpty()) return Result.success()

        val projectKeywords = container.settingsRepository.projectKeywords.first()
        var newObviousFiles = 0
        var newFiles = 0

        for (root in roots) {
            if (isStopped) return Result.retry()

            val before = withContext(Dispatchers.IO) {
                container.database.fileRecordDao()
                    .getFilesUnderScopeRoot(root)
                    .mapTo(hashSetOf()) { it.stableRef }
            }

            runCatching {
                container.fileScanner(StorageAccessMode.DIRECT)
                    .scan(FileRef.Direct(root))
            }.getOrElse {
                return Result.retry()
            }

            val after = withContext(Dispatchers.IO) {
                container.database.fileRecordDao().getFilesUnderScopeRoot(root)
            }
            val added = after.filter { it.stableRef !in before && !it.isHidden }
            newFiles += added.size
            newObviousFiles += added.count { record ->
                !RuleEngine.classify(
                    record.displayName,
                    record.extension,
                    projectKeywords,
                ).isUncategorized()
            }
        }

        if (newFiles > 0) {
            postSuggestion(newFiles, newObviousFiles)
        }
        return Result.success()
    }

    private fun postSuggestion(newFiles: Int, obvious: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Cleanup suggestions",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Review-only suggestions for newly discovered files."
            },
        )
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Pocket Steward found new files")
                .setContentText(
                    if (obvious > 0) {
                        "$newFiles new · $obvious have obvious organization matches. Open Pocket Steward to review."
                    } else {
                        "$newFiles new files are ready to review."
                    },
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "pocket_steward_cleanup_suggestions"
        const val NOTIFICATION_ID = 1401
    }
}
