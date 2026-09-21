package com.pocketsteward.app

import android.content.Intent
import android.os.Bundle
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

    private fun render() {
        val afterOnboarding = when (intent?.data?.host?.lowercase()) {
            "explore", "search" -> Routes.SCAN_FLOW
            "tasks" -> Routes.HISTORY
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
