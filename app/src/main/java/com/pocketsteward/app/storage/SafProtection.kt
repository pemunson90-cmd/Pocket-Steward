package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Provider IDs remain opaque. Missing root/ancestry/list access is never treated as permission. */
internal class SafProtection(private val context: Context) {
    private val resolver = context.contentResolver
    private data class Node(val id: String, val name: String, val directory: Boolean)
    private data class Route(val tree: Uri, val path: List<String>)

    private fun children(tree: Uri, id: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val result = mutableListOf<Node>()
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                check(result.size < 100_000) { "Protection check exceeds the bounded folder limit" }
                result += Node(
                    cursor.getString(0) ?: error("Missing provider identity"),
                    cursor.getString(1) ?: error("Missing filename"),
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
            check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "Provider listing still loading" }
        } ?: error("Provider listing unavailable")
        return result
    }

    private fun route(source: Uri): Route {
        val authority = source.authority ?: error("Provider unavailable")
        val document = DocumentsContract.getDocumentId(source)
        val roots = mutableSetOf<String>()
        resolver.query(
            DocumentsContract.buildRootsUri(authority),
            arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { roots += it }
        } ?: error("Provider root information unavailable")

        val grants = (
            listOf(source) + resolver.persistedUriPermissions
                .filter { it.isReadPermission && it.uri.authority == authority && DocumentsContract.isTreeUri(it.uri) }
                .map { it.uri }
            ).distinct()
        for (tree in grants) {
            if (!DocumentsContract.isTreeUri(tree)) continue
            val path = runCatching {
                DocumentsContract.findDocumentPath(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, document),
                )?.path
            }.getOrNull() ?: continue
            if (path.firstOrNull() in roots && path.lastOrNull() == document) return Route(tree, path)
        }
        error("Select a provider root that exposes the complete ancestry; a narrow folder grant cannot verify protection above it")
    }

    fun refusal(source: Uri): String? = try {
        val (tree, path) = route(source)
        check(path.size > 1) { "Provider root cannot be moved" }
        for (id in path.dropLast(1)) {
            if (children(tree, id).any { it.name == DirectProtection.MARKER && !it.directory }) {
                return "Protected: an ancestor contains ${DirectProtection.MARKER}"
            }
        }
        val target = children(tree, path[path.size - 2]).singleOrNull { it.id == path.last() }
            ?: error("Source ancestry changed")
        if (target.name == DirectProtection.MARKER) {
            return "Protected: no-sort marker requires deliberate unprotect review."
        }
        val pending = java.util.ArrayDeque<Node>()
        pending.add(target)
        val visited = mutableSetOf<String>()
        var count = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            check(++count <= 100_000) { "Protection subtree check exceeds limit" }
            if (node.directory) {
                check(visited.add(node.id)) { "Provider reports cyclic or ambiguous directory ancestry" }
                val entries = children(tree, node.id)
                if (entries.any { it.name == DirectProtection.MARKER && !it.directory }) {
                    return "Protected: a contained folder has ${DirectProtection.MARKER}"
                }
                check(count + pending.size + entries.size <= 100_000) { "Protection subtree check exceeds limit" }
                pending.addAll(entries)
            }
        }
        null
    } catch (e: Exception) {
        "Protection unverifiable: ${e.message ?: "provider access needed"}"
    }

    /** Checks the real, existing parent immediately before a child is created or restored. */
    fun refusalDestination(parent: Uri, proposedName: String): String? = try {
        if (proposedName == DirectProtection.MARKER) {
            return "Protected: the no-sort marker can only be created through deliberate protection controls."
        }
        val (tree, path) = route(parent)
        for (id in path) {
            if (children(tree, id).any { it.name == DirectProtection.MARKER && !it.directory }) {
                return "Protected: a destination ancestor contains ${DirectProtection.MARKER}"
            }
        }
        null
    } catch (e: Exception) {
        "Protection unverifiable: ${e.message ?: "destination ancestry needed"}"
    }
}
