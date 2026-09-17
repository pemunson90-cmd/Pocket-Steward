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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Plan Section 18: reconcile before anything else touches the
        // journal. Cheap (a handful of rows at most for a personal app) and
        // has to happen before any new scan or organize run so a crash
        // that interrupted a previous run never gets mistaken for one that
        // completed cleanly.
        applicationScope.launch {
            container.reconciliationService.reconcileInterruptedRuns()
        }
    }
}
