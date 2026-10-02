package com.pocketsteward.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Local only. Always delegates to Android's fatal handler; never resumes a crashed process. */
object LocalCrashDiagnostics {
    @Volatile var activeRoute: String = "startup"
    private val writing = AtomicBoolean(false)
    private const val FILE_NAME = "last-crash.txt"
    private const val DISMISSED_EXIT = "dismissed-exit.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val version = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (writing.compareAndSet(false, true)) {
                    val report = CrashReport.format(error, version,
                        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        activeRoute, thread === Looper.getMainLooper().thread)
                    File(app.filesDir, FILE_NAME).writeText(report)
                }
            } catch (_: Throwable) {
                // Disk full or exhausted memory must not replace the original fatal exception.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    /** Also exposes Android's exit reason for builds that predate our exception recorder. */
    fun read(context: Context): String? {
        val app = context.applicationContext
        val report = File(app.filesDir, FILE_NAME)
        if (report.isFile) return report.reader().use { reader ->
            val chars = CharArray(CrashReport.MAX_CHARS)
            val count = reader.read(chars)
            if (count > 0) String(chars, 0, count) else null
        }
        val exit = latestFailure(app) ?: return null
        val dismissed = File(app.filesDir, DISMISSED_EXIT).takeIf(File::isFile)?.readText()?.toLongOrNull() ?: 0L
        if (exit.timestamp <= dismissed) return null
        return "Pocket Steward previous process exit\n" +
            "Reason: ${reason(exit.reason)}\nAndroid API: ${Build.VERSION.SDK_INT}\n" +
            "Time (epoch ms): ${exit.timestamp}\n" +
            "Android recorded the exit reason. This older build did not save an exception stack."
    }

    fun dismiss(context: Context) {
        val app = context.applicationContext
        File(app.filesDir, DISMISSED_EXIT).writeText((latestFailure(app)?.timestamp ?: 0L).toString())
        File(app.filesDir, FILE_NAME).delete()
    }

    private fun latestFailure(context: Context): ApplicationExitInfo? =
        context.getSystemService(ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            ?.firstOrNull { it.processName == context.packageName && it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
            ) }

    private fun reason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "App not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android stopped the app for low memory"
        else -> "Unknown"
    }
}
