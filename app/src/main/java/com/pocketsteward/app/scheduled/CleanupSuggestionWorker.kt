package com.pocketsteward.app.scheduled

import androidx.core.content.ContextCompat

import androidx.core.app.NotificationManagerCompat

import android.os.Build

import android.content.pm.PackageManager

import android.Manifest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.pocketsteward.app.storage.StorageScope
import com.pocketsteward.app.storage.rawValue
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
        val mode = access.mode ?: return Result.success()
        val roots = when (mode) {
            StorageAccessMode.DIRECT -> {
                val configured = if (settings.roots.isNotEmpty()) {
                    settings.roots
                } else {
                    container.database.fileRecordDao().getKnownScopeRoots()
                }
                configured
                    .map { it.trimEnd('/') }
                    .filter { it.isNotBlank() && File(it).isDirectory }
                    .distinct()
                    .map(FileRef::Direct)
            }

            StorageAccessMode.SAF -> {
                val treeUri = access.safTreeUri ?: return Result.success()
                val root = runCatching {
                    container.gatewayFor(StorageAccessMode.SAF)
                        .rootOf(
                            StorageScope.Tree(
                                rootRef = FileRef.Saf(treeUri),
                                displayName = "Selected folder",
                            ),
                        )
                }.getOrNull() ?: return Result.retry()

                val configured = settings.roots
                    .map { it.trimEnd('/') }
                    .filter { it.isNotBlank() }
                if (configured.isNotEmpty() && root.rawValue().trimEnd('/') !in configured) {
                    // The schedule was saved for a different tree. Do not
                    // silently scan a newly granted folder instead.
                    return Result.success()
                }
                listOf(root)
            }
        }

        if (roots.isEmpty()) return Result.success()

        val projectKeywords = container.settingsRepository.projectKeywords.first()
        var newObviousFiles = 0
        var newFiles = 0

        for (root in roots) {
            if (isStopped) return Result.retry()
            val rootRaw = root.rawValue().trimEnd('/')

            val before = withContext(Dispatchers.IO) {
                container.database.fileRecordDao()
                    .getFilesUnderScopeRoot(rootRaw)
                    .mapTo(hashSetOf()) { it.stableRef }
            }

            runCatching {
                container.fileScanner(mode).scan(root)
            }.getOrElse {
                return Result.retry()
            }

            val after = withContext(Dispatchers.IO) {
                container.database.fileRecordDao().getFilesUnderScopeRoot(rootRaw)
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
            container.settingsRepository.setPendingCleanupSuggestion(
                PendingCleanupSuggestion(
                    createdAtEpochMs = System.currentTimeMillis(),
                    roots = roots.map { it.rawValue() },
                    newFileCount = newFiles,
                    obviousMatchCount = newObviousFiles,
                ),
            )
            postSuggestion(newFiles, newObviousFiles)
        }
        return Result.success()
    }

    private fun postSuggestion(newFiles: Int, obvious: Int) {
        val permissionGranted =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted ||
            !NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        ) {
            // The pending review was persisted before this call. Notification
            // permission controls surfacing only, never whether the finding
            // survives for Home to show later.
            return
        }

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
                data = Uri.parse("pocketsteward://scheduled")
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
