package com.pocketsteward.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pocketsteward.app.MainActivity
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w900dp-h900dp", application = TabTestApplication::class, shadows = [TabEnvironment::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColdTabNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualLauncherCanOpenHomeAndExploreOnExpandedAndroid16Layout() {
        val app = ApplicationProvider.getApplicationContext<PocketStewardApplication>()
        runBlocking { app.container.settingsRepository.setStorageAccessMode(StorageAccessMode.DIRECT) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Files", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Pocket Steward").assertIsDisplayed()
        compose.onNodeWithContentDescription("Files", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Explore", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Choose where to look").assertIsDisplayed()
        compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Pocket Steward").assertIsDisplayed()
    }
}
