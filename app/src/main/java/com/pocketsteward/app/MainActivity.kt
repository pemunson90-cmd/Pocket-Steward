package com.pocketsteward.app

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
        setContent {
            PocketStewardTheme {
                PocketStewardNavHost(startDestination = Routes.ONBOARDING)
            }
        }
    }
}
