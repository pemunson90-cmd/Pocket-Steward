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
        val resolved = resolve(context, path) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resolved.uri, resolved.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, "Open artifact"), "No app can open this artifact.")
    }

    fun share(context: Context, path: String) {
        val resolved = resolve(context, path) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = resolved.mimeType
            putExtra(Intent.EXTRA_STREAM, resolved.uri)
            putExtra(Intent.EXTRA_SUBJECT, resolved.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, "Share artifact"), "No app can share this artifact.")
    }

    fun showContainingFolder(context: Context, path: String) {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val treeUri = runCatching {
                DocumentsContract.buildTreeDocumentUri(
                    uri.authority,
                    DocumentsContract.getTreeDocumentId(uri),
                )
            }.getOrNull()
            if (treeUri == null) {
                Toast.makeText(context, "This provider cannot expose the containing folder.", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            }
            launch(context, intent, "No folder browser is available.")
            return
        }

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

    private data class ResolvedArtifact(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
    )

    private fun resolve(context: Context, path: String): ResolvedArtifact? {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val mime = context.contentResolver.getType(uri)
                ?: mimeFromName(uri.lastPathSegment.orEmpty())
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Pocket Steward artifact"
            return ResolvedArtifact(uri, name, mime)
        }

        val file = File(path)
        if (!file.isFile) {
            Toast.makeText(context, "Artifact file is no longer present.", Toast.LENGTH_SHORT).show()
            return null
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return ResolvedArtifact(uri, file.name, mimeFromName(file.name))
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "csv" -> "text/csv"
        "md", "markdown" -> "text/markdown"
        else -> "text/plain"
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
