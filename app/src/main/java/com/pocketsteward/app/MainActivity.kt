package com.pocketsteward.app

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.pocketsteward.app.ui.onboarding.StorageAccessGrantPolicy
import com.pocketsteward.app.storage.StorageAccessMode
import androidx.lifecycle.lifecycleScope
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pocketsteward.app.navigation.PocketStewardNavHost
import com.pocketsteward.app.navigation.Routes
import com.pocketsteward.app.ui.theme.PocketStewardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Static launcher shortcuts can arrive while the task already exists.
        // Recompose from the shortcut target rather than silently ignoring it.
        render()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val container = (application as PocketStewardApplication).container
            val state = container.settingsRepository.storageAccessState.first()
            if (state.mode == null) return@launch

            val safGrant = state.safTreeUri?.let { saved ->
                contentResolver.persistedUriPermissions.firstOrNull {
                    it.uri.toString() == saved
                }
            }
            val usable = StorageAccessGrantPolicy.isUsable(
                state = state,
                broadAccessGranted = Environment.isExternalStorageManager(),
                safReadGranted = safGrant?.isReadPermission == true,
                safWriteGranted = safGrant?.isWritePermission == true,
            )
            if (!usable) {
                container.settingsRepository.clearStorageAccessChoice()
                render()
            }
        }
    }

    private fun render() {
        val afterOnboarding = when (intent?.data?.host?.lowercase()) {
            "explore", "search" -> Routes.SCAN_FLOW
            "tasks" -> Routes.HISTORY
            "scheduled" -> Routes.scanFlowScheduledReview()
            else -> Routes.HOME
        }
        setContent {
            PocketStewardTheme {
                PocketStewardNavHost(
                    startDestination = Routes.ONBOARDING,
                    postOnboardingDestination = afterOnboarding,
                )
            }
        }
    }
}
