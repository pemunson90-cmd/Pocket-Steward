package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef
import java.net.URLDecoder

/**
 * The plan drawn as two folder trees: the files it touches where they are
 * now, and where they will be after it runs. A list of 300 "Move a/b to c/b"
 * rows is accurate and unreadable; a tree shows the shape of the result,
 * which is what a person is actually approving.
 *
 * Pure Kotlin on purpose (no Android imports) so it is unit tested like
 * [PlanValidator]. It is display only. It never feeds the executor, and it
 * cannot change what runs: the selected operations do that, unchanged.
 */
object PlanTree {

    enum class Mark {
        /** Before: this file is moved elsewhere. After: this file arrives here. */
        MOVE,
        /** Before: this file gets a new name. After: the new name. */
        RENAME,
        /** Before: this file is copied. After: the new copy. */
        COPY,
        /** Before: this file goes to the recoverable Trash. After: its place inside Trash. */
        TRASH,
        /** After only: a folder or file the plan creates. */
        NEW,
    }

    data class Row(
        val depth: Int,
        val name: String,
        val isFolder: Boolean,
        /** Number of plan-touched files at or below this row. 1 for a file. */
        val fileCount: Int,
        val mark: Mark?,
    )

    data class Tree(
        /** Common path shared by every row, shown once as a heading instead of repeated on each row. */
        val rootLabel: String,
        val rows: List<Row>,
        /** Rows left out because the tree was longer than the cap. */
        val hiddenRows: Int,
    )

    data class BeforeAfter(val before: Tree, val after: Tree)

    /** Label of the virtual folder that stands for the app's Trash in the after tree. */
    const val TRASH_LABEL = "Pocket Steward Trash (recoverable)"

    fun build(operations: List<PlannedOperation>, maxRows: Int = 600): BeforeAfter {
        val before = mutableListOf<Pair<List<String>, Leaf>>()
        val after = mutableListOf<Pair<List<String>, Leaf>>()

        for (op in operations) {
            when (op) {
                is PlannedOperation.Move -> {
                    before += segments(op.source) to Leaf(false, Mark.MOVE)
                    after += segments(op.destination) to Leaf(false, Mark.MOVE)
                }
                is PlannedOperation.Copy -> {
                    before += segments(op.source) to Leaf(false, Mark.COPY)
                    after += segments(op.source) to Leaf(false, null)
                    after += segments(op.destination) to Leaf(false, Mark.COPY)
                }
                is PlannedOperation.Rename -> {
                    val src = segments(op.source)
                    before += src to Leaf(false, Mark.RENAME)
                    after += (src.dropLast(1) + op.newName) to Leaf(false, Mark.RENAME)
                }
                is PlannedOperation.Trash -> {
                    val src = segments(op.source)
                    before += src to Leaf(false, Mark.TRASH)
                    after += listOf(TRASH_LABEL, src.lastOrNull() ?: "file") to Leaf(false, Mark.TRASH)
                }
                is PlannedOperation.CreateDirectory ->
                    after += (segments(op.parent) + op.name) to Leaf(true, Mark.NEW)
                is PlannedOperation.WriteTextFile ->
                    after += (segments(op.parent) + op.name) to Leaf(false, Mark.NEW)
            }
        }

        // One shared root for both trees, so "Download" sits at the same
        // depth in Now and After and the two read side by side.
        val prefix = commonPrefix(
            (before + after).map { it.first }.filter { it.firstOrNull() != TRASH_LABEL },
        )
        return BeforeAfter(
            before = render(before, prefix, maxRows),
            after = render(after, prefix, maxRows),
        )
    }

    private data class Leaf(val isFolder: Boolean, val mark: Mark?)

    private class Node(val name: String) {
        val children = sortedMapOf<String, Node>(String.CASE_INSENSITIVE_ORDER)
        var isFolder = false
        var isLeaf = false
        var mark: Mark? = null
        var fileCount = 0
    }

    private fun render(entries: List<Pair<List<String>, Leaf>>, prefix: List<String>, maxRows: Int): Tree {
        val root = Node("")
        for ((path, leaf) in entries) {
            val rel = if (path.firstOrNull() == TRASH_LABEL) path else path.drop(prefix.size)
            if (rel.isEmpty()) continue
            var node = root
            for ((i, segment) in rel.withIndex()) {
                node = node.children.getOrPut(segment) { Node(segment) }
                if (i < rel.lastIndex) node.isFolder = true
            }
            node.isLeaf = true
            if (leaf.isFolder) node.isFolder = true
            // A folder the plan creates and then fills keeps its NEW mark.
            if (node.mark == null || leaf.mark == Mark.NEW) node.mark = leaf.mark ?: node.mark
        }
        countFiles(root)

        val rows = mutableListOf<Row>()
        var hidden = 0
        fun walk(node: Node, depth: Int) {
            // Folders first, then files, each alphabetical: the order every
            // file manager uses, so nothing about the tree has to be learned.
            val (folders, files) = node.children.values.partition { it.isFolder }
            for (child in folders + files) {
                var shown = child
                var label = child.name
                // Collapse a chain of single-child folders ("Documents/Tax/2025")
                // into one row; deep paths otherwise push names off-screen.
                while (shown.isFolder && !shown.isLeaf && shown.children.size == 1 &&
                    shown.children.values.first().isFolder
                ) {
                    shown = shown.children.values.first()
                    label = "$label/${shown.name}"
                }
                if (rows.size >= maxRows) {
                    hidden++
                } else {
                    rows += Row(depth, label, shown.isFolder, shown.fileCount, shown.mark)
                }
                walk(shown, depth + 1)
            }
        }
        walk(root, 0)
        return Tree(
            rootLabel = prefix.joinToString("/").let { if (it.isEmpty()) "/" else friendlyRoot(it) },
            rows = rows,
            hiddenRows = hidden,
        )
    }

    private fun countFiles(node: Node): Int {
        val own = if (node.isLeaf && !node.isFolder) 1 else 0
        node.fileCount = own + node.children.values.sumOf { countFiles(it) }
        return node.fileCount
    }

    private fun friendlyRoot(joined: String): String =
        joined
            .replaceFirst(Regex("^storage/emulated/0(?=/|$)"), "Phone storage")
            .replaceFirst(Regex("^sdcard(?=/|$)"), "Phone storage")

    internal fun commonPrefix(paths: List<List<String>>): List<String> {
        if (paths.isEmpty()) return emptyList()
        // Never swallow a file name into the root: the prefix stops at the
        // shortest path's parent.
        val limit = paths.minOf { it.size } - 1
        val out = mutableListOf<String>()
        for (i in 0 until limit.coerceAtLeast(0)) {
            val segment = paths[0][i]
            if (paths.all { it[i] == segment }) out += segment else break
        }
        return out
    }

    /** Path segments for display. Structural only: no provider is asked anything. */
    internal fun segments(ref: FileRef): List<String> = when (ref) {
        is FileRef.Direct -> ref.absolutePath.split('/').filter { it.isNotEmpty() }
        is FileRef.Saf -> safSegments(ref.documentUri)
        is FileRef.Child -> segments(ref.parent) + ref.name
    }

    /**
     * External-storage document IDs look like `primary:Download/Invoices/a.pdf`
     * once decoded, so the readable path is the part after the volume colon.
     * Other providers use opaque IDs; those still render, as one segment.
     */
    internal fun safSegments(uri: String): List<String> {
        val encodedId = uri.substringAfterLast("/document/", missingDelimiterValue = uri.substringAfterLast('/'))
        val id = try {
            URLDecoder.decode(encodedId.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            encodedId
        }
        val path = id.substringAfter(':', id)
        return path.split('/').filter { it.isNotEmpty() }.ifEmpty { listOf(id) }
    }
}
