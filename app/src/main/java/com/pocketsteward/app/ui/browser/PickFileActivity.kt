package com.pocketsteward.app.ui.browser

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.data.settings.UiSettings
import com.pocketsteward.app.ui.theme.PocketStewardTheme

/**
 * Pocket Steward as a file picker: when another app asks for a file ("attach",
 * "upload", "choose file"), the system can offer this alongside its own
 * picker. Read-only: it browses, then hands back a read grant for the chosen
 * files. The browser's change actions are hidden in this mode.
 */
class PickFileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val request = PickRequest(
            // A caller often sets type */* and lists what it really wants in
            // EXTRA_MIME_TYPES; the list is the stricter, truer answer.
            mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.filter { it.isNotBlank() }?.ifEmpty { null }
                ?: listOfNotNull(intent.type).ifEmpty { listOf("*/*") },
            multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
        )
        val settings = (application as PocketStewardApplication).container.settingsRepository
        setContent {
            val ui by settings.uiSettings.collectAsState(initial = UiSettings())
            PocketStewardTheme(wallpaperColors = ui.wallpaperColorsEnabled, thumbnailsEnabled = ui.thumbnailsEnabled) {
                BrowserScreen(
                    pick = request,
                    onPicked = { items -> finishWith(items) },
                    onCancelPick = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    private fun finishWith(items: List<com.pocketsteward.app.browser.BrowserItem>) {
        val uris = items.filter { !it.isDirectory }.mapNotNull { contentUriFor(this, it) }
        if (uris.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent().apply {
            data = uris.first()
            clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
