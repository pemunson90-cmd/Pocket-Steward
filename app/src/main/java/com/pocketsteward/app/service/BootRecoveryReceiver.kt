package com.pocketsteward.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes Pocket Steward after an ordinary completed boot.
 *
 * Recovery itself intentionally lives in PocketStewardApplication: starting
 * the process runs the same journal reconciliation and durable-task/content-
 * index resume policy used after any process death. This receiver performs no
 * filesystem mutation and cannot invent a task. CANCELLED (user-paused) tasks
 * are not auto-resumed because startup only selects RUNNING durable tasks.
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // No direct work here. Creating this receiver instance has already
        // started the application process and therefore Application.onCreate,
        // which owns the single recovery path.
    }
}
