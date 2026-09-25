package com.pocketsteward.app.ui.share

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketsteward.app.MainActivity
import com.pocketsteward.app.rules.RuleEngine
import com.pocketsteward.app.ui.theme.PocketStewardTheme

/**
 * Share-sheet intake stays deliberately user-driven.
 *
 * Pocket Steward inspects only the incoming file's name/type, suggests a
 * deterministic category, then Android's system CreateDocument picker owns the
 * destination choice. The app never deletes or moves the original share
 * source, and never writes until the user explicitly chooses a destination.
 */
class ShareIntakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stream = if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else {
            null
        }
        if (stream == null) {
            finish()
            return
        }

        val info = inspect(stream, intent.type)
        setContent {
            PocketStewardTheme {
                ShareIntakeScreen(
                    uri = stream,
                    info = info,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onFinished = { finish() },
                )
            }
        }
    }

    private fun inspect(uri: Uri, fallbackMime: String?): SharedFileInfo {
        var name: String? = null
        var size: Long? = null
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val displayName = name ?: uri.lastPathSegment ?: "shared-file"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val mime = contentResolver.getType(uri)
            ?: fallbackMime
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val classification = RuleEngine.classify(displayName, extension)

        return SharedFileInfo(
            displayName = displayName,
            extension = extension,
            mimeType = mime,
            sizeBytes = size,
            suggestedCategory = classification.category.name
                .lowercase()
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() },
            reason = classification.reason,
        )
    }
}

data class SharedFileInfo(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val suggestedCategory: String,
    val reason: String,
)

@Composable
private fun ShareIntakeScreen(
    uri: Uri,
    info: SharedFileInfo,
    onOpenApp: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(info.mimeType),
    ) { destination ->
        if (destination != null) {
            val result = runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not read the shared file." }
                    context.contentResolver.openOutputStream(destination, "w").use { output ->
                        requireNotNull(output) { "Could not open the chosen destination." }
                        input.copyTo(output)
                        output.flush()
                    }
                }
            }
            if (result.isSuccess) {
                Toast.makeText(context, "Saved a copy. Original file was not changed.", Toast.LENGTH_LONG).show()
                onFinished()
            } else {
                Toast.makeText(
                    context,
                    result.exceptionOrNull()?.message ?: "Could not save the shared file.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            Text("Shared with Pocket Steward", style = MaterialTheme.typography.headlineMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(info.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(info.suggestedCategory)
                            info.sizeBytes?.let { append(" · ").append(it).append(" bytes") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        info.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Text(
                "Saving uses Android's system document picker. Pocket Steward never moves or deletes the shared original.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { saveLauncher.launch(info.displayName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose where to save a copy")
            }

            OutlinedButton(
                onClick = onOpenApp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Pocket Steward")
            }
            }
        }
    }
}
