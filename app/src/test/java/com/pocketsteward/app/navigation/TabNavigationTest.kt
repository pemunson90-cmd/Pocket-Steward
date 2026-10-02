package com.pocketsteward.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.ui.theme.PocketStewardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/** Exercises real destination composition and saved tab stacks without starting a scan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = TabTestApplication::class, shadows = [TabEnvironment::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filesHomeExploreAndRestoredTabsComposeWithoutScanning() {
        val app = ApplicationProvider.getApplicationContext<PocketStewardApplication>()
        runBlocking {
            app.container.settingsRepository.setStorageAccessMode(StorageAccessMode.DIRECT)
        }
        compose.setContent {
            PocketStewardTheme {
                PocketStewardNavHost(startDestination = Routes.HOME)
            }
        }
        compose.onNodeWithContentDescription("Files", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        repeat(3) {
            compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Pocket Steward").assertIsDisplayed()
            compose.onNodeWithContentDescription("Explore", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Choose where to look").assertIsDisplayed()
            compose.onNodeWithContentDescription("Files", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Explore", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Choose where to look").assertIsDisplayed()
            compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Pocket Steward").assertIsDisplayed()
        }
    }

    @Test fun homeWithLargeCompletedTaskHistoryDoesNotReadWholePlans() {
        val app = ApplicationProvider.getApplicationContext<PocketStewardApplication>()
        runBlocking {
            app.container.database.taskRunDao().insert(TaskRun(
                requestText = "Large completed filing task", startedAt = 1L, completedAt = 2L,
                status = TaskRunStatus.COMPLETED, scanSnapshotId = null,
                planJson = "x".repeat(5 * 1024 * 1024), summary = "Completed",
                scopeRootRef = "/storage/emulated/0/Download", storageAccessMode = StorageAccessMode.DIRECT,
                undoCompletedAt = null,
            ))
        }
        compose.setContent {
            PocketStewardTheme { PocketStewardNavHost(startDestination = Routes.HOME) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Pocket Steward").assertIsDisplayed()
    }

    @Test fun restartCrashNoticeCanCopyAndDismissWithoutOpeningSettings() {
        val app = ApplicationProvider.getApplicationContext<PocketStewardApplication>()
        val record = "Pocket Steward crash record\njava.lang.IllegalArgumentException\n  at ExampleNav.lookup:42"
        val file = java.io.File(app.filesDir, "last-crash.txt")
        file.writeText(record)
        compose.setContent { PocketStewardTheme { com.pocketsteward.app.diagnostics.CrashNotice() } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Previous app failure").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Copy report").performClick()
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        org.junit.Assert.assertEquals(record, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Dismiss record").performClick()
        compose.onNodeWithText("Previous app failure").assertDoesNotExist()
        compose.waitUntil(10_000) { !file.exists() }
    }
}

class TabTestApplication : PocketStewardApplication() {
    override fun onCreate() {
        // Robolectric does not invoke AndroidX startup providers as a device does.
        if (runCatching { androidx.work.WorkManager.getInstance(this) }.isFailure) {
            androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().build())
        }
        super.onCreate()
    }
}

// Robolectric has no mounted shared-storage volume; the real device has one.
@Implements(android.os.Environment::class)
class TabEnvironment : ShadowEnvironment() {
    companion object {
        @JvmStatic @Implementation fun isExternalStorageManager(): Boolean = true
    }
}
