package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Strict provider observations: partial/unavailable listings cannot prove an empty folder. */
internal class SafDocuments(private val context: Context) {
    private val resolver = context.contentResolver
    data class Node(val uri: Uri, val id: String, val name: String, val mime: String,
        val size: Long, val modified: Long?, val flags: Int) {
        val directory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS)

    fun documentUri(uri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(uri,
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri)
        else DocumentsContract.getTreeDocumentId(uri))

    fun children(parent: Uri): List<Node> = query(parent,
        DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(documentUri(parent))))

    fun find(uri: Uri): Node? = query(uri, documentUri(uri)).also { check(it.size <= 1) { "Provider document identity is ambiguous." } }.singleOrNull()

    fun stat(uri: Uri): Node = requireNotNull(find(uri)) { "Provider document no longer exists." }.also {
        check(it.directory || it.size >= 0) { "Provider file size is unavailable; this source needs review." }
    }

    private fun query(tree: Uri, queryUri: Uri): List<Node> {
        val result = ArrayList<Node>()
        val identities = HashSet<String>()
        var estimatedBytes = 0L
        resolver.query(queryUri, columns, null, null, null)?.use { cursor ->
            val indexes = columns.map { cursor.getColumnIndexOrThrow(it) }
            while (cursor.moveToNext()) {
                check(result.size < 100_000) { "Provider listing exceeds the bounded folder limit." }
                val id = cursor.getString(indexes[0])?.takeIf { it.isNotBlank() } ?: error("Missing provider identity.")
                check(identities.add(id)) { "Provider listing repeats a document identity." }
                val name = cursor.getString(indexes[1]) ?: error("Missing provider filename.")
                require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && name.none { it.isISOControl() }) {
                    "Provider filename cannot be represented as a safe child."
                }
                val mime = cursor.getString(indexes[2]) ?: error("Missing provider document type.")
                val size = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) 0L else {
                    if (cursor.isNull(indexes[3])) -1L else cursor.getLong(indexes[3])
                }
                val childUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                estimatedBytes += 256L + 2L * (id.length + name.length + mime.length + childUri.toString().length)
                check(estimatedBytes <= 32L * 1024 * 1024) { "Provider listing exceeds the bounded metadata memory budget." }
                result += Node(childUri, id, name, mime, size,
                    if (cursor.isNull(indexes[4])) null else cursor.getLong(indexes[4]).takeIf { it > 0 },
                    if (cursor.isNull(indexes[5])) 0 else cursor.getInt(indexes[5]))
            }
            check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "Provider listing is still loading; retry when it finishes." }
            check(cursor.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) { "Provider listing failed: ${cursor.extras.getString(DocumentsContract.EXTRA_ERROR)}" }
        } ?: error("Provider listing is unavailable.")
        return result
    }

    /** Parent/name comes from the provider path and document metadata, never an ID prefix. */
    fun location(uri: Uri): FileRef.Child {
        val concrete = documentUri(uri)
        val id = DocumentsContract.getDocumentId(concrete)
        val root = DocumentsContract.getTreeDocumentId(concrete)
        val path = DocumentsContract.findDocumentPath(resolver, concrete)?.path ?: error("Provider ancestry is unavailable.")
        check(path.lastOrNull() == id && root in path && path.distinct().size == path.size) { "Provider ancestry is ambiguous or outside the grant." }
        check(path.indexOf(root) < path.lastIndex) { "The granted root has no accessible parent and cannot be moved." }
        val parent = DocumentsContract.buildDocumentUriUsingTree(concrete, path[path.lastIndex - 1])
        // The provider path establishes the parent; querying that document establishes its name.
        // Listing every sibling for each source would make a 16k-file review quadratic.
        val child = stat(concrete)
        check(child.id == id) { "Provider source identity changed." }
        return FileRef.Child(FileRef.Saf(parent.toString()), child.name)
    }

    fun sameDocument(first: Uri, second: Uri): Boolean =
        first.authority == second.authority && DocumentsContract.getDocumentId(documentUri(first)) == DocumentsContract.getDocumentId(documentUri(second))
}
