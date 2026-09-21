package com.pocketsteward.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pocketsteward.app.MainActivity
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.R
import com.pocketsteward.app.content.index.ContentIndexCandidate
import com.pocketsteward.app.content.index.ContentIndexJobStatus
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only foreground runner for the derived content index.
 *
 * This is intentionally separate from FileTaskForegroundService. It has no
 * PlanExecutor, mutation journal, or filesystem mutation authority. Its only
 * job is to extract searchable text into content_search.db, with a durable
 * cursor after every file so Android timeouts/process death can resume.
 */
class ContentIndexForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pauseRequested = AtomicBoolean(false)
    private var runningJob: Job? = null
    private var activeRoot: String? = null

    private val container
        get() = (application as PocketStewardApplication).container

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                pauseRequested.set(true)
                updateNotification(
                    title = "Pausing content indexing",
                    text = "Finishing the current file safely…",
                    indeterminate = true,
                )
                return START_NOT_STICKY
            }

            ACTION_RUN -> {
                val roots = intent.getStringArrayListExtra(EXTRA_ROOTS)
                    ?.map { it.trimEnd('/') }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    .orEmpty()
                if (roots.isEmpty()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (runningJob?.isActive == true) return START_NOT_STICKY

                pauseRequested.set(false)
                startForegroundCompat(
                    buildNotification(
                        title = "Pocket Steward is indexing",
                        text = "Preparing searchable document content…",
                        indeterminate = true,
                    ),
                )
                runningJob = scope.launch {
                    runRoots(roots, startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runRoots(roots: List<String>, startId: Int) {
        val repository = container.contentIndexRepository(StorageAccessMode.DIRECT)
        try {
            var rootsCompleted = 0
            for (root in roots) {
                if (pauseRequested.get()) break
                activeRoot = root

                val records = container.database.fileRecordDao()
                    .getFilesUnderScopeRoot(root)
                val candidates = records.map { record ->
                    ContentIndexCandidate(record = record, sourceRoot = root)
                }

                val finalJob = repository.refreshRootResumable(
                    candidates = candidates,
                    sourceRoot = root,
                    shouldPause = pauseRequested::get,
                ) { job ->
                    updateNotification(
                        title = if (pauseRequested.get()) {
                            "Pausing content indexing"
                        } else {
                            "Indexing ${root.substringAfterLast('/').ifBlank { "files" }}"
                        },
                        text = buildString {
                            append("${job.processedCount} of ${job.eligibleCount} files")
                            if (roots.size > 1) {
                                append(" · folder ${rootsCompleted + 1} of ${roots.size}")
                            }
                        },
                        completed = job.processedCount,
                        total = job.eligibleCount,
                    )
                }

                if (finalJob.status == ContentIndexJobStatus.PAUSED.name) break
                rootsCompleted++
            }

            if (pauseRequested.get()) {
                postTerminalNotification(
                    title = "Content indexing paused",
                    text = "Progress is saved and will resume at the next file.",
                )
            } else {
                postTerminalNotification(
                    title = "Content index ready",
                    text = "Search can reuse indexed document contents.",
                )
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                activeRoot?.let { repository.markPaused(it, "Indexing was interrupted and can resume.") }
            }
            throw cancel
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                activeRoot?.let { repository.markPaused(it, t.message ?: "Indexing stopped.") }
            }
            postTerminalNotification(
                title = "Content indexing stopped",
                text = "Progress was saved. It can resume later.",
            )
        } finally {
            activeRoot = null
            runningJob = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runningJob?.cancel(CancellationException("Android dataSync foreground-service timeout"))
        stopSelf(startId)
    }

    override fun onDestroy() {
        runningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun buildNotification(
        title: String,
        text: String,
        completed: Int = 0,
        total: Int = 0,
        indeterminate: Boolean = total <= 0,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                data = Uri.parse("pocketsteward://explore")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            11,
            pauseIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), completed.coerceAtLeast(0), indeterminate)
            .addAction(0, "Pause", pauseIntent)
            .build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        completed: Int = 0,
        total: Int = 0,
        indeterminate: Boolean = total <= 0,
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text, completed, total, indeterminate))
    }

    private fun postTerminalNotification(title: String, text: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                data = Uri.parse("pocketsteward://explore")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(NotificationManager::class.java).notify(
            TERMINAL_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Content indexing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for local document indexing."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "pocket_steward_content_index"
        private const val NOTIFICATION_ID = 1301
        private const val TERMINAL_NOTIFICATION_ID = 1302
        private const val ACTION_RUN = "com.pocketsteward.app.action.RUN_CONTENT_INDEX"
        private const val ACTION_PAUSE = "com.pocketsteward.app.action.PAUSE_CONTENT_INDEX"
        private const val EXTRA_ROOTS = "source_roots"

        fun runIntent(context: Context, roots: List<String>): Intent =
            Intent(context, ContentIndexForegroundService::class.java)
                .setAction(ACTION_RUN)
                .putStringArrayListExtra(EXTRA_ROOTS, ArrayList(roots))

        fun pauseIntent(context: Context): Intent =
            Intent(context, ContentIndexForegroundService::class.java)
                .setAction(ACTION_PAUSE)
    }
}
