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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground runner for already-approved durable tasks.
 *
 * The Intent contains only a taskRunId. Planning, validation and user
 * selection happened before this service was started and are durably stored
 * in TaskRun.planJson. This service cannot invent or edit a plan.
 */
class FileTaskForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pauseRequested = AtomicBoolean(false)
    private var runningJob: Job? = null
    private var activeTaskRunId: Long? = null

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
                if (runningJob?.isActive != true) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                pauseRequested.set(true)
                updateNotification(
                    title = "Pausing Pocket Steward",
                    text = "Finishing the current file operation safely…",
                    indeterminate = true,
                )
                return START_NOT_STICKY
            }

            ACTION_RUN -> {
                val taskRunId = intent.getLongExtra(EXTRA_TASK_RUN_ID, -1L)
                if (taskRunId <= 0L) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (runningJob?.isActive == true) {
                    // One mutation runner per process. The durable task remains
                    // untouched if a second start request arrives.
                    return START_NOT_STICKY
                }
                activeTaskRunId = taskRunId
                pauseRequested.set(false)
                startForegroundCompat(
                    buildNotification(
                        title = "Pocket Steward is working",
                        text = "Recovering task state…",
                        indeterminate = true,
                    ),
                )
                runningJob = serviceScope.launch {
                    runTask(taskRunId, startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTask(taskRunId: Long, startId: Int) {
        try {
            container.mutationRecovery.recoverAll()
            val task = container.database.taskRunDao().getById(taskRunId)
                ?: error("Task no longer exists.")
            val executor = container.planExecutor(task.storageAccessMode)
            val result = executor.resume(
                taskRunId = taskRunId,
                shouldPause = pauseRequested::get,
            ) { completed, total ->
                updateNotification(
                    title = if (pauseRequested.get()) {
                        "Pausing Pocket Steward"
                    } else {
                        "Pocket Steward is organizing files"
                    },
                    text = if (total > 0) {
                        "$completed of $total operations"
                    } else {
                        "Preparing task…"
                    },
                    completed = completed,
                    total = total,
                )
            }

            if (result.cancelled) {
                postTerminalNotification(
                    title = "Pocket Steward paused",
                    text = "Progress was saved. Resume the task from Tasks.",
                )
            } else {
                postTerminalNotification(
                    title = "Pocket Steward finished",
                    text = "${result.succeededTotal} succeeded · ${result.failed} failed",
                )
            }
        } catch (cancel: CancellationException) {
            // Forced service shutdown or system timeout. Do not rewrite task
            // state here: MutationRecovery will resolve any PENDING row and
            // the durable plan still identifies every missing sequence.
            throw cancel
        } catch (security: SecurityException) {
            container.database.taskRunDao().markRunningPaused(
                id = taskRunId,
                completedAt = System.currentTimeMillis(),
                summary = "Paused because storage access is unavailable. Restore access, then Resume from Tasks.",
            )
            postTerminalNotification(
                title = "Pocket Steward needs storage access",
                text = "The approved task is saved. Restore access, then Resume from Tasks.",
            )
        } catch (t: Throwable) {
            postTerminalNotification(
                title = "Pocket Steward stopped",
                text = t.message ?: "The task needs attention.",
            )
        } finally {
            activeTaskRunId = null
            runningJob = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    /**
     * Android 15+ dataSync foreground-service timeout. Stop promptly. If this
     * interrupts one atomic filesystem call, its PENDING journal row is
     * resolved conservatively by MutationRecovery on the next app/service
     * start before the remaining durable plan can continue.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runningJob?.cancel(CancellationException("Android dataSync foreground-service timeout"))
        stopSelf(startId)
    }

    override fun onDestroy() {
        runningJob?.cancel()
        serviceScope.cancel()
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
                data = Uri.parse("pocketsteward://tasks")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
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
            .notify(
                NOTIFICATION_ID,
                buildNotification(title, text, completed, total, indeterminate),
            )
    }

    private fun postTerminalNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                data = Uri.parse("pocketsteward://tasks")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
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
            "File tasks",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for Pocket Steward file operations."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "pocket_steward_file_tasks"
        private const val NOTIFICATION_ID = 1201
        private const val TERMINAL_NOTIFICATION_ID = 1202
        private const val ACTION_RUN = "com.pocketsteward.app.action.RUN_DURABLE_TASK"
        private const val ACTION_PAUSE = "com.pocketsteward.app.action.PAUSE_DURABLE_TASK"
        private const val EXTRA_TASK_RUN_ID = "task_run_id"

        fun runIntent(context: Context, taskRunId: Long): Intent =
            Intent(context, FileTaskForegroundService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_TASK_RUN_ID, taskRunId)

        fun pauseIntent(context: Context): Intent =
            Intent(context, FileTaskForegroundService::class.java)
                .setAction(ACTION_PAUSE)
    }
}
