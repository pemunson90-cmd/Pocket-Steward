package com.pocketsteward.app.provider

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.pocketsteward.app.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Pocket Steward as a source in Android's file picker (read-only).
 *
 * Tapping "Pocket Steward" in any app's picker opens:
 *   Recent        newest files from anywhere in shared storage (MediaStore index, instant)
 *   Downloads, Documents, Pictures, Camera   shortcuts, when those folders exist
 *   All storage   the whole of shared storage
 *   Pocket Steward   the app's own managed folder (Trash excluded)
 * Folders list first, files newest first. The root also supports recents and
 * search, so the picker's own Recent view and search box include these files.
 *
 * Read-only by construction: openDocument accepts mode "r" only and no
 * create/rename/delete operations are implemented. Hidden paths, Android/ and
 * PocketSteward/Trash are never listed or opened. Without all-files access the
 * source falls back to the managed folder only.
 */
class PocketStewardDocumentsProvider : DocumentsProvider() {
    private val storageRoot: File
        get() = Environment.getExternalStorageDirectory().canonicalFile

    private val hasAllFilesAccess: Boolean
        get() = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    override fun onCreate(): Boolean {
        runCatching { File(Environment.getExternalStorageDirectory(), PickerPaths.MANAGED_DIR).mkdirs() }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(columns).apply {
            val row = newRow()
            put(row, columns, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            put(row, columns, DocumentsContract.Root.COLUMN_DOCUMENT_ID, PickerPaths.VIRTUAL_ROOT)
            put(row, columns, DocumentsContract.Root.COLUMN_TITLE, "Pocket Steward")
            put(row, columns, DocumentsContract.Root.COLUMN_SUMMARY, "Recent files first")
            put(
                row, columns, DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                    DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                    DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
            put(row, columns, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            put(row, columns, DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            when (documentId) {
                PickerPaths.VIRTUAL_ROOT -> includeVirtualDir(newRow(), columns, PickerPaths.VIRTUAL_ROOT, "Pocket Steward")
                PickerPaths.VIRTUAL_RECENT -> includeVirtualDir(newRow(), columns, PickerPaths.VIRTUAL_RECENT, "Recent")
                else -> {
                    val file = fileForId(documentId)
                    if (!file.exists()) throw FileNotFoundException(documentId)
                    includeFile(newRow(), columns, file)
                }
            }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            when (parentDocumentId) {
                PickerPaths.VIRTUAL_ROOT -> listRoot(this, columns)
                PickerPaths.VIRTUAL_RECENT -> recentFiles(RECENT_FOLDER_LIMIT).forEach { includeFile(newRow(), columns, it, summary = parentLabel(it)) }
                else -> {
                    val parent = fileForId(parentDocumentId)
                    if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
                    val children = parent.listFiles()?.filter { isPickableFile(it) }.orEmpty()
                    PickerPaths.sortForPicking(children) { PickerPaths.Entry(it.name, it.isDirectory, it.lastModified()) }
                        .forEach { includeFile(newRow(), columns, it) }
                }
            }
        }
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            if (rootId != ROOT_ID) return@apply
            recentFiles(RECENTS_VIEW_LIMIT).forEach { includeFile(newRow(), columns, it, summary = parentLabel(it)) }
        }
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val needle = query.trim()
        return MatrixCursor(columns).apply {
            if (rootId != ROOT_ID || needle.isBlank()) return@apply
            val hits = if (hasAllFilesAccess) mediaStoreSearch(needle) else walkSearch(managedDir(), needle)
            hits.forEach { includeFile(newRow(), columns, it, summary = parentLabel(it)) }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Pocket Steward's picker source is read-only.")
        val file = fileForId(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (documentId == PickerPaths.VIRTUAL_ROOT || documentId == PickerPaths.VIRTUAL_RECENT) {
            return parentDocumentId == PickerPaths.VIRTUAL_ROOT && documentId == PickerPaths.VIRTUAL_RECENT
        }
        val child = runCatching { fileForId(documentId) }.getOrNull() ?: return false
        if (parentDocumentId == PickerPaths.VIRTUAL_ROOT) return true
        if (parentDocumentId == PickerPaths.VIRTUAL_RECENT) return false
        val parent = runCatching { fileForId(parentDocumentId) }.getOrNull() ?: return false
        return child.path.startsWith(parent.path.trimEnd(File.separatorChar) + File.separator)
    }

    private fun listRoot(cursor: MatrixCursor, columns: Array<out String>) {
        if (hasAllFilesAccess) {
            cursor.includeVirtualDir(cursor.newRow(), columns, PickerPaths.VIRTUAL_RECENT, "Recent")
            PickerPaths.SHORTCUTS.forEach { (folder, label) ->
                val dir = File(storageRoot, folder)
                if (dir.isDirectory) cursor.includeFile(cursor.newRow(), columns, dir, displayName = label)
            }
            cursor.includeFile(cursor.newRow(), columns, storageRoot, displayName = "All storage")
        }
        val managed = managedDir()
        if (managed.isDirectory) cursor.includeFile(cursor.newRow(), columns, managed, displayName = "Pocket Steward")
    }

    /** Newest files across shared storage, from Android's own media index. */
    private fun recentFiles(limit: Int): List<File> {
        if (!hasAllFilesAccess) {
            return walkAll(managedDir(), limit * 4)
                .sortedByDescending { it.lastModified() }
                .take(limit)
        }
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL AND ${MediaStore.Files.FileColumns.SIZE} > 0",
            )
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit * 3)
        }
        return mediaStorePaths(args).take(limit)
    }

    private fun mediaStoreSearch(needle: String): List<File> {
        val escaped = needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%$escaped%"))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_SEARCH_RESULTS * 2)
        }
        return mediaStorePaths(args).take(MAX_SEARCH_RESULTS)
    }

    @Suppress("DEPRECATION") // DATA is readable with all-files access and is the only path column.
    private fun mediaStorePaths(args: Bundle): List<File> {
        val resolver = context?.contentResolver ?: return emptyList()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val out = ArrayList<File>()
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DATA), args, null)?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext()) {
                    val path = c.getString(col) ?: continue
                    val file = File(path)
                    if (file.isFile && isPickableFile(file)) out += file
                }
            }
        }
        return out
    }

    private fun walkSearch(start: File, needle: String): List<File> =
        walkAll(start, MAX_WALK).filter { it.name.contains(needle, ignoreCase = true) }.take(MAX_SEARCH_RESULTS)

    private fun walkAll(start: File, cap: Int): List<File> {
        val out = ArrayList<File>()
        val queue = ArrayDeque<File>().apply { add(start) }
        while (queue.isNotEmpty() && out.size < cap) {
            queue.removeFirst().listFiles()?.forEach { f ->
                if (!isPickableFile(f)) return@forEach
                if (f.isDirectory) queue += f else out += f
            }
        }
        return out
    }

    private fun managedDir(): File = File(storageRoot, PickerPaths.MANAGED_DIR)

    private fun relativeOf(file: File): String? {
        val root = storageRoot
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (canonical == root) return ""
        val prefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!canonical.path.startsWith(prefix)) return null
        return PickerPaths.normalize(canonical.path.removePrefix(prefix))
    }

    private fun isPickableFile(file: File): Boolean {
        if (!hasAllFilesAccess) {
            val rel = relativeOf(file) ?: return false
            if (rel != PickerPaths.MANAGED_DIR && !rel.startsWith(PickerPaths.MANAGED_DIR + "/")) return false
        }
        val rel = relativeOf(file) ?: return false
        return PickerPaths.isPickable(rel)
    }

    private fun fileForId(documentId: String): File {
        val relative = PickerPaths.relativeFor(documentId) ?: throw FileNotFoundException(documentId)
        if (!PickerPaths.isPickable(relative)) throw FileNotFoundException(documentId)
        val candidate = File(storageRoot, relative).canonicalFile
        // Canonicalizing resolves symlinks; re-check that the real target is still inside and pickable.
        if (!isPickableFile(candidate)) throw FileNotFoundException("Document is outside Pocket Steward's picker scope.")
        return candidate
    }

    private fun idForFile(file: File): String =
        PickerPaths.idFor(relativeOf(file) ?: throw FileNotFoundException(file.name))

    private fun parentLabel(file: File): String? =
        relativeOf(file)?.substringBeforeLast('/', "")?.ifEmpty { "Internal storage" }

    private fun MatrixCursor.includeVirtualDir(row: MatrixCursor.RowBuilder, columns: Array<out String>, id: String, name: String) {
        put(row, columns, DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
        put(row, columns, DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
        put(row, columns, DocumentsContract.Document.COLUMN_SIZE, 0L)
        put(row, columns, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        put(row, columns, DocumentsContract.Document.COLUMN_LAST_MODIFIED, null)
        put(row, columns, DocumentsContract.Document.COLUMN_FLAGS, 0)
    }

    private fun MatrixCursor.includeFile(
        row: MatrixCursor.RowBuilder,
        columns: Array<out String>,
        file: File,
        displayName: String? = null,
        summary: String? = null,
    ) {
        put(row, columns, DocumentsContract.Document.COLUMN_DOCUMENT_ID, idForFile(file))
        put(row, columns, DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName ?: file.name)
        put(row, columns, DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
        put(row, columns, DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
        put(row, columns, DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified().takeIf { it > 0 })
        put(row, columns, DocumentsContract.Document.COLUMN_SUMMARY, summary)
        put(row, columns, DocumentsContract.Document.COLUMN_FLAGS, 0)
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun put(row: MatrixCursor.RowBuilder, columns: Array<out String>, column: String, value: Any?) {
        if (column in columns) row.add(column, value)
    }

    private companion object {
        const val ROOT_ID = "pocket-steward"
        const val MAX_SEARCH_RESULTS = 100
        const val RECENT_FOLDER_LIMIT = 150
        const val RECENTS_VIEW_LIMIT = 64
        const val MAX_WALK = 5000

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
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
            DocumentsContract.Document.COLUMN_SUMMARY,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
