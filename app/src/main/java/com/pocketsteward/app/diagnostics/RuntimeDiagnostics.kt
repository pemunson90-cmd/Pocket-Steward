package com.pocketsteward.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import com.pocketsteward.app.content.index.ContentIndexOverview
import com.pocketsteward.app.data.db.AppDatabase

data class RuntimeDiagnosticsSnapshot(
    val appVersion: String,
    val fileRecords: Int,
    val scopeRoots: Int,
    val taskRuns: Int,
    val journalRows: Int,
    val contentDocuments: Int,
    val contentSegments: Int,
    val contentRoots: Int,
    val lastTaskDurationMs: Long?,
    val powerSaveMode: Boolean,
    val batteryOptimizationExempt: Boolean,
    val lowRamDevice: Boolean,
    val sharedStorageUsableBytes: Long,
)

/**
 * Privacy-safe local runtime diagnostics.
 *
 * No filenames, paths, content, model prompts, or task request text leave this
 * object. It exists to make the Milestone 7 performance/battery requirement
 * inspectable on the real phone instead of relying on vibes.
 */
class RuntimeDiagnostics(
    context: Context,
    private val database: AppDatabase,
    private val contentIndexOverview: suspend () -> ContentIndexOverview,
) {
    private val appContext = context.applicationContext

    suspend fun snapshot(): RuntimeDiagnosticsSnapshot {
        val power = appContext.getSystemService(PowerManager::class.java)
        val activity = appContext.getSystemService(ActivityManager::class.java)
        val index = contentIndexOverview()
        val recent = database.taskRunDao().getMostRecentCompleted()

        val version = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")

        @Suppress("DEPRECATION")
        val sharedRoot = Environment.getExternalStorageDirectory()

        return RuntimeDiagnosticsSnapshot(
            appVersion = version,
            fileRecords = database.fileRecordDao().countAllRecords(),
            scopeRoots = database.fileRecordDao().getKnownScopeRoots().size,
            taskRuns = database.taskRunDao().countAllRuns(),
            journalRows = database.mutationRecordDao().countAllMutations(),
            contentDocuments = index.documentCount,
            contentSegments = index.segmentCount,
            contentRoots = index.rootCount,
            lastTaskDurationMs = recent?.completedAt
                ?.let { completed -> (completed - recent.startedAt).coerceAtLeast(0L) },
            powerSaveMode = power.isPowerSaveMode,
            batteryOptimizationExempt = power.isIgnoringBatteryOptimizations(appContext.packageName),
            lowRamDevice = activity.isLowRamDevice,
            sharedStorageUsableBytes = runCatching { sharedRoot.usableSpace }.getOrDefault(0L),
        )
    }
}
