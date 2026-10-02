package com.pocketsteward.app.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reachable on restart even when opening a tab itself is what crashes. */
@Composable
fun CrashNotice() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        report = withContext(Dispatchers.IO) { runCatching { LocalCrashDiagnostics.read(context) }.getOrNull() }
    }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("Previous app failure") },
        text = {
            SelectionContainer {
                Text(text, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Pocket Steward crash record", text))
            }) { Text("Copy report") }
        },
        dismissButton = {
            TextButton(onClick = {
                report = null
                scope.launch(Dispatchers.IO) { runCatching { LocalCrashDiagnostics.dismiss(context) } }
            }) { Text("Dismiss record") }
        },
    )
}
