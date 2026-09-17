package com.pocketsteward.app

import android.app.Application
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
    }
}
