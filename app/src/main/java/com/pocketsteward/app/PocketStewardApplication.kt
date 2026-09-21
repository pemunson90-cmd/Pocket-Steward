package com.pocketsteward.app

import android.app.Application
import com.pocketsteward.app.content.index.ContentIndexJobStatus
import com.pocketsteward.app.content.index.ContentSearchDatabase
import com.pocketsteward.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PocketStewardApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Resolve any write-ahead rows left by process death before the next
        // user-initiated task relies on journal/history state. Recovery is
        // deliberately conservative: ambiguous filesystem states become
        // NEEDS_REVIEW rather than being guessed.
        appScope.launch {
            runCatching { container.mutationRecovery.recoverAll() }
        }

        // A process death during read-only indexing leaves the current job
        // RUNNING with a file-granular cursor. When the user opens the app
        // again, restart only jobs that were running/queued; an explicit
        // user pause stays paused.
        appScope.launch {
            val jobs = runCatching {
                ContentSearchDatabase.getInstance(this@PocketStewardApplication)
                    .contentIndexDao()
                    .getResumableJobs()
            }.getOrDefault(emptyList())
            val roots = jobs
                .filter {
                    it.status == ContentIndexJobStatus.RUNNING.name ||
                        it.status == ContentIndexJobStatus.QUEUED.name
                }
                .map { it.sourceRoot }
            if (roots.isNotEmpty()) {
                container.startContentIndexing(roots)
            }
        }
    }
}
