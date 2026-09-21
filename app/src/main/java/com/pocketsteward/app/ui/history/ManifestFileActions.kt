package com.pocketsteward.app.ui.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

internal object ManifestFileActions {
    fun open(context: Context, path: String) {
        val file = File(path)
        if (!file.isFile) {
            Toast.makeText(context, "Manifest file is no longer present.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val mime = if (file.extension.equals("json", ignoreCase = true)) "application/json" else "text/markdown"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, "Open manifest"), "No app can open this manifest.")
    }

    fun share(context: Context, path: String) {
        val file = File(path)
        if (!file.isFile) {
            Toast.makeText(context, "Manifest file is no longer present.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val mime = if (file.extension.equals("json", ignoreCase = true)) "application/json" else "text/markdown"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, "Share manifest"), "No app can share this manifest.")
    }

    fun showContainingFolder(context: Context, path: String) {
        val parent = File(path).parentFile
        if (parent == null || !parent.isDirectory) {
            Toast.makeText(context, "Containing folder is no longer present.", Toast.LENGTH_SHORT).show()
            return
        }

        val initial = externalStorageDocumentUri(parent.absolutePath)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            initial?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        launch(context, intent, "No folder browser is available.")
    }

    private fun externalStorageDocumentUri(path: String): Uri? {
        val primary = "/storage/emulated/0"
        if (path != primary && !path.startsWith("$primary/")) return null
        val relative = path.removePrefix(primary).trimStart('/')
        val documentId = if (relative.isBlank()) "primary:" else "primary:$relative"
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            documentId,
        )
    }

    private fun launch(
        context: Context,
        intent: Intent,
        failureMessage: String,
    ) {
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
            }
    }
}
