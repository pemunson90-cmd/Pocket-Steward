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
import com.pocketsteward.app.navigation.LauncherEntryPolicy
import com.pocketsteward.app.ui.theme.PocketStewardTheme
import com.pocketsteward.app.data.settings.UiSettings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private val entryRevision = androidx.compose.runtime.mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryRevision.longValue = savedInstanceState?.getLong("launcherEntryRevision", 0L) ?: 0L
        enableEdgeToEdge()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        entryRevision.longValue++
        // Static launcher shortcuts can arrive while the task already exists.
        // Recompose from the shortcut target rather than silently ignoring it.
        render()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val container = (application as PocketStewardApplication).container
            container.inboxObservation.refreshPermissions()
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("launcherEntryRevision", entryRevision.longValue)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val afterOnboarding = LauncherEntryPolicy.destination(intent?.data?.scheme, intent?.data?.host)
        val settingsRepository = (application as PocketStewardApplication).container.settingsRepository
        setContent {
            val ui by settingsRepository.uiSettings.collectAsState(initial = UiSettings())
            PocketStewardTheme(
                wallpaperColors = ui.wallpaperColorsEnabled,
                thumbnailsEnabled = ui.thumbnailsEnabled,
            ) {
                PocketStewardNavHost(
                    startDestination = Routes.ONBOARDING,
                    postOnboardingDestination = afterOnboarding,
                    launcherEntryRevision = entryRevision.longValue,
                )
                com.pocketsteward.app.diagnostics.CrashNotice()
            }
        }
    }
}
