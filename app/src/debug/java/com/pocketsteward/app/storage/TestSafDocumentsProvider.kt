package com.pocketsteward.app.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Test-only writable SAF provider backed by the instrumentation APK's cache.
 *
 * It exercises Pocket Steward's real DocumentFile/DocumentsContract code path
 * without depending on OEM or cloud providers in CI. The target-device Samsung
 * provider pass still matters, but this closes the structural SAF mutation gap.
 */
class TestSafDocumentsProvider : DocumentsProvider() {
    private val root: File
        get() = File(requireNotNull(context).cacheDir, "ps-test-saf-root")

    override fun onCreate(): Boolean {
        root.mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: ROOT_COLUMNS
        return MatrixCursor(cols).apply {
            val row = newRow()
            put(row, cols, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            put(row, cols, DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            put(row, cols, DocumentsContract.Root.COLUMN_TITLE, "Pocket Steward Test SAF")
            put(
                row,
                cols,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
            put(row, cols, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val cols = projection ?: DOC_COLUMNS
        val file = fileForId(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(cols).apply {
            includeFile(newRow(), cols, file)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DOC_COLUMNS
        val parent = fileForId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(cols).apply {
            parent.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { includeFile(newRow(), cols, it) }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileForId(documentId)
        if (!file.exists() || file.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        requireSafeName(displayName)
        val parent = fileForId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val target = File(parent, displayName)
        if (target.exists()) throw FileNotFoundException("already exists: $displayName")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            target.mkdir()
        } else {
            target.createNewFile()
        }
        if (!created) throw FileNotFoundException("could not create: $displayName")
        return idForFile(target)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireSafeName(displayName)
        val source = fileForId(documentId)
        if (!source.exists() || source == root) throw FileNotFoundException(documentId)
        val target = File(requireNotNull(source.parentFile), displayName)
        if (target.exists()) throw FileNotFoundException("already exists: $displayName")
        if (!source.renameTo(target)) throw FileNotFoundException("rename failed")
        return idForFile(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileForId(documentId)
        if (!file.exists() || file == root) throw FileNotFoundException(documentId)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) throw FileNotFoundException("delete failed: $documentId")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { fileForId(parentDocumentId).canonicalFile }.getOrNull() ?: return false
        val child = runCatching { fileForId(documentId).canonicalFile }.getOrNull() ?: return false
        return child.path.startsWith(parent.path.trimEnd(File.separatorChar) + File.separator)
    }

    override fun findDocumentPath(
        parentDocumentId: String?,
        childDocumentId: String,
    ): DocumentsContract.Path {
        val canonicalRoot = root.canonicalFile
        val child = fileForId(childDocumentId).canonicalFile
        if (child != canonicalRoot &&
            !child.path.startsWith(canonicalRoot.path.trimEnd(File.separatorChar) + File.separator)
        ) {
            throw FileNotFoundException(childDocumentId)
        }

        val pathIds = mutableListOf(ROOT_ID)
        if (child != canonicalRoot) {
            var current = canonicalRoot
            child.relativeTo(canonicalRoot)
                .invariantSeparatorsPath
                .split('/')
                .filter(String::isNotBlank)
                .forEach { segment ->
                    current = File(current, segment)
                    pathIds += idForFile(current)
                }
        }

        parentDocumentId?.let { parentId ->
            if (parentId !in pathIds) {
                throw FileNotFoundException("Parent is not an ancestor: $parentId")
            }
        }
        return DocumentsContract.Path(ROOT_ID, pathIds)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_RESET) {
            root.deleteRecursively()
            root.mkdirs()
            return Bundle.EMPTY
        }
        return super.call(method, arg, extras)
    }

    private fun MatrixCursor.includeFile(
        row: MatrixCursor.RowBuilder,
        columns: Array<out String>,
        file: File,
    ) {
        put(row, columns, DocumentsContract.Document.COLUMN_DOCUMENT_ID, idForFile(file))
        put(
            row,
            columns,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            if (file == root) "root" else file.name,
        )
        put(
            row,
            columns,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            if (file.isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                when (file.extension.lowercase()) {
                    "txt", "md" -> "text/plain"
                    "json" -> "application/json"
                    else -> "application/octet-stream"
                }
            },
        )
        put(row, columns, DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
        put(row, columns, DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        put(
            row,
            columns,
            DocumentsContract.Document.COLUMN_FLAGS,
            if (file.isDirectory) {
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
            } else {
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
            },
        )
    }

    private fun fileForId(documentId: String): File {
        if (documentId == ROOT_ID) return root.canonicalFile
        if (!documentId.startsWith(DOC_PREFIX)) throw FileNotFoundException(documentId)
        val relative = documentId.removePrefix(DOC_PREFIX)
        val candidate = File(root, relative).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (!candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            throw FileNotFoundException("escaped root")
        }
        return candidate
    }

    private fun idForFile(file: File): String {
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        if (canonical == canonicalRoot) return ROOT_ID
        return DOC_PREFIX + canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
    }

    private fun requireSafeName(name: String) {
        require(
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                '/' !in name &&
                '\\' !in name &&
                name.none { it.isISOControl() },
        )
    }

    private fun put(
        row: MatrixCursor.RowBuilder,
        columns: Array<out String>,
        column: String,
        value: Any?,
    ) {
        if (column in columns) row.add(column, value)
    }

    companion object {
        const val AUTHORITY = "com.pocketsteward.app.test.saf"
        const val ROOT_ID = "root"
        const val METHOD_RESET = "reset"

        private const val DOC_PREFIX = "doc:"

        val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )

        val DOC_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
