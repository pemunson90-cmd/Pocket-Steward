package com.pocketsteward.app.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.pocketsteward.app.R
import java.io.File
import java.io.FileNotFoundException
import java.util.Base64

/**
 * Read-only Android DocumentsProvider for Pocket Steward's own managed folder.
 *
 * This exposes PocketSteward/ (including Trash) to Android's document picker
 * without creating a mutation bypass. The provider supports browse/search/read
 * only; organization still goes through plan -> validator -> preview ->
 * executor -> journal.
 */
class PocketStewardDocumentsProvider : DocumentsProvider() {
    private val baseDir: File
        get() = File(Environment.getExternalStorageDirectory(), MANAGED_DIR)

    override fun onCreate(): Boolean {
        // This is the app's own managed root. Creating the container if absent
        // does not touch a user file and makes the provider stable before the
        // first trash operation.
        runCatching { baseDir.mkdirs() }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(columns).apply {
            val row = newRow()
            put(row, columns, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            put(row, columns, DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            put(row, columns, DocumentsContract.Root.COLUMN_TITLE, "Pocket Steward")
            put(
                row,
                columns,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                    DocumentsContract.Root.FLAG_SUPPORTS_SEARCH,
            )
            put(row, columns, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            put(row, columns, DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val file = fileForId(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(columns).apply { includeFile(newRow(), columns, file) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val parent = fileForId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(columns).apply {
            parent.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { includeFile(newRow(), columns, it) }
        }
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val needle = query.trim()
        return MatrixCursor(columns).apply {
            if (rootId != ROOT_ID || needle.isBlank()) return@apply
            var emitted = 0
            val queue = ArrayDeque<File>()
            queue += baseDir
            while (queue.isNotEmpty() && emitted < MAX_SEARCH_RESULTS) {
                val dir = queue.removeFirst()
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) queue += file
                    if (file.name.contains(needle, ignoreCase = true)) {
                        includeFile(newRow(), columns, file)
                        emitted++
                        if (emitted >= MAX_SEARCH_RESULTS) return@forEach
                    }
                }
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Pocket Steward's document provider is read-only.")
        }
        val file = fileForId(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        val parent = runCatching { fileForId(parentDocumentId).canonicalFile }.getOrNull() ?: return false
        val child = runCatching { fileForId(documentId).canonicalFile }.getOrNull() ?: return false
        return child.path.startsWith(parent.path.trimEnd(File.separatorChar) + File.separator)
    }

    private fun MatrixCursor.includeFile(
        row: MatrixCursor.RowBuilder,
        columns: Array<out String>,
        file: File,
    ) {
        put(row, columns, DocumentsContract.Document.COLUMN_DOCUMENT_ID, idForFile(file))
        put(row, columns, DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name.ifBlank { "Pocket Steward" })
        put(row, columns, DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
        put(row, columns, DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
        put(row, columns, DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified().takeIf { it > 0 })
        put(row, columns, DocumentsContract.Document.COLUMN_FLAGS, 0)
    }

    private fun fileForId(documentId: String): File {
        val base = baseDir.canonicalFile
        if (documentId == ROOT_DOCUMENT_ID) return base

        if (!documentId.startsWith(DOC_PREFIX)) throw FileNotFoundException(documentId)
        val encoded = documentId.removePrefix(DOC_PREFIX)
        val relative = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrElse { throw FileNotFoundException(documentId) }

        val candidate = File(base, relative).canonicalFile
        if (candidate.path != base.path &&
            !candidate.path.startsWith(base.path.trimEnd(File.separatorChar) + File.separator)
        ) {
            throw FileNotFoundException("Document id escapes managed storage.")
        }
        return candidate
    }

    private fun idForFile(file: File): String {
        val base = baseDir.canonicalFile
        val canonical = file.canonicalFile
        if (canonical == base) return ROOT_DOCUMENT_ID
        val relative = canonical.relativeTo(base).invariantSeparatorsPath
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(relative.toByteArray(Charsets.UTF_8))
        return DOC_PREFIX + encoded
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun put(
        row: MatrixCursor.RowBuilder,
        columns: Array<out String>,
        column: String,
        value: Any?,
    ) {
        if (column in columns) row.add(column, value)
    }

    private companion object {
        const val MANAGED_DIR = "PocketSteward"
        const val ROOT_ID = "pocket-steward"
        const val ROOT_DOCUMENT_ID = "ps-root"
        const val DOC_PREFIX = "ps:"
        const val MAX_SEARCH_RESULTS = 100

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
