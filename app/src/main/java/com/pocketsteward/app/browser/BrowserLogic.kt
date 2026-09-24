package com.pocketsteward.app.browser

import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.cleanup.DO_NOT_SORT_TEMPLATE
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.rawValue

/** One row in a folder listing, category or search result. */
data class BrowserItem(
    val ref: FileRef,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val mimeType: String?,
    val isHidden: Boolean,
    /** Where the item lives, for category and search rows that mix folders. */
    val parentRef: FileRef?,
    /** Entries inside a folder, when cheap to know. */
    val childCount: Int? = null,
) {
    val key: String get() = ref.rawValue()
}

enum class SortKey(val label: String) { NAME("Name"), MODIFIED("Date"), SIZE("Size"), TYPE("Type") }

data class BrowserSort(val key: SortKey = SortKey.NAME, val descending: Boolean = false)

enum class ClipMode { MOVE, COPY }

data class Clipboard(val mode: ClipMode, val items: List<BrowserItem>)

/** A folder walked ahead of a copy, since the validator copies files only. */
data class WalkedFolder(
    val ref: FileRef,
    val name: String,
    val files: List<Pair<FileRef, String>>,
    val folders: List<WalkedFolder>,
) {
    val fileCount: Int get() = files.size + folders.sumOf { it.fileCount }
}

/**
 * The browser's decisions, free of Android and unit tested. Every change it
 * proposes is an ordinary PlannedOperation: the screen validates it, shows a
 * confirmation, and hands it to the executor, which journals it for undo.
 */
object BrowserLogic {

    fun sort(items: List<BrowserItem>, sort: BrowserSort, showHidden: Boolean): List<BrowserItem> {
        val visible = if (showHidden) items else items.filter { !it.isHidden && !it.name.startsWith(".") }
        val byKey: Comparator<BrowserItem> = when (sort.key) {
            SortKey.NAME -> compareBy(NaturalOrder) { it.name }
            SortKey.MODIFIED -> compareBy<BrowserItem> { it.modifiedAt ?: 0L }.thenBy(NaturalOrder) { it.name }
            SortKey.SIZE -> compareBy<BrowserItem> { if (it.isDirectory) (it.childCount ?: 0).toLong() else it.sizeBytes }
                .thenBy(NaturalOrder) { it.name }
            SortKey.TYPE -> compareBy<BrowserItem> { extensionOf(it.name) }.thenBy(NaturalOrder) { it.name }
        }
        val directed = if (sort.descending) byKey.reversed() else byKey
        // Folders stay on top in every order, the way every file manager does it.
        return visible.sortedWith(compareByDescending<BrowserItem> { it.isDirectory }.then(directed))
    }

    /**
     * "report.pdf" taken → "report (2).pdf", then (3)... Case-insensitive,
     * because shared storage is. A trailing " (n)" already on the name is
     * continued rather than stacked.
     */
    fun uniqueName(name: String, taken: Set<String>): String {
        val lowerTaken = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lowerTaken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        val m = Regex("""^(.*) \((\d{1,4})\)$""").find(base)
        val stem = m?.groupValues?.get(1) ?: base
        var n = (m?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase() !in lowerTaken) return candidate
            n++
        }
    }

    /** A name the storage layer and the validator will accept as one path segment. */
    fun validName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return "Enter a name."
        if (trimmed.any { it == '/' || it == '\\' || it.code < 32 }) return "Names can't contain / or \\."
        if (trimmed.length > 255) return "That name is too long."
        return null
    }

    data class PasteResult(val operations: List<PlannedOperation>, val skipped: List<String>)

    /**
     * Move or copy [clipboard] into [destination]. Names already in the
     * destination get "(2)" rather than a collision. Pasting a cut item into
     * the folder it came from is a no-op; moving a folder into itself or
     * below itself is refused. Folder copies arrive pre-walked in
     * [walkedFolders] and expand into create-folder and copy-file steps.
     */
    fun paste(
        clipboard: Clipboard,
        destination: FileRef,
        destinationNames: Set<String>,
        walkedFolders: Map<String, WalkedFolder> = emptyMap(),
    ): PasteResult {
        val ops = mutableListOf<PlannedOperation>()
        val skipped = mutableListOf<String>()
        val taken = destinationNames.toMutableSet()
        val destKey = destination.rawValue().trimEnd('/')

        for (item in clipboard.items) {
            val itemKey = item.key.trimEnd('/')
            val sameFolder = item.parentRef?.rawValue()?.trimEnd('/') == destKey
            if (clipboard.mode == ClipMode.MOVE && sameFolder) {
                skipped += "${item.name} is already here."
                continue
            }
            if (item.isDirectory && (destKey == itemKey || destKey.startsWith("$itemKey/"))) {
                skipped += "${item.name} can't go inside itself."
                continue
            }
            val name = uniqueName(item.name, taken)
            taken += name
            val target = destination.child(name)
            when {
                clipboard.mode == ClipMode.MOVE ->
                    ops += PlannedOperation.Move(item.ref, target, moveReason(item.name, name))
                !item.isDirectory ->
                    ops += PlannedOperation.Copy(item.ref, target, copyReason(item.name, name))
                else -> {
                    val walked = walkedFolders[item.key]
                    if (walked == null) {
                        skipped += "${item.name} couldn't be read to copy."
                    } else {
                        expandFolderCopy(walked, destination, name, ops)
                    }
                }
            }
        }
        return PasteResult(ops, skipped)
    }

    private fun expandFolderCopy(folder: WalkedFolder, parent: FileRef, name: String, ops: MutableList<PlannedOperation>) {
        ops += PlannedOperation.CreateDirectory(parent, name, "Copy of folder ${folder.name}.")
        val created = parent.child(name)
        folder.files.forEach { (ref, fileName) ->
            ops += PlannedOperation.Copy(ref, created.child(fileName), "Copied with folder ${folder.name}.")
        }
        folder.folders.forEach { sub -> expandFolderCopy(sub, created, sub.name, ops) }
    }

    fun rename(item: BrowserItem, newName: String): PlannedOperation.Rename =
        PlannedOperation.Rename(item.ref, newName.trim(), "Renamed from ${item.name}.")

    fun newFolder(parent: FileRef, name: String): PlannedOperation.CreateDirectory =
        PlannedOperation.CreateDirectory(parent, name.trim(), "New folder.")

    fun trash(items: List<BrowserItem>): List<PlannedOperation> =
        items.map { PlannedOperation.Trash(it.ref, "Moved to Pocket Steward's recoverable Trash from the Files tab.") }

    fun protect(folder: FileRef): PlannedOperation.WriteTextFile =
        PlannedOperation.WriteTextFile(folder, DO_NOT_SORT_MARKER, DO_NOT_SORT_TEMPLATE, "Protects this folder from sorting.")

    private fun moveReason(from: String, to: String) = if (from == to) "Moved from the Files tab." else "Moved and named $to, since $from was taken."
    private fun copyReason(from: String, to: String) = if (from == to) "Copied from the Files tab." else "Copied as $to, since $from was taken."

    /**
     * The app's own Trash area: `PocketSteward` at the storage root and its
     * `Trash` folder. Journals point into it, so moving or renaming it would
     * break Undo and restore for everything already trashed. Files inside
     * Trash are restored from the Trash screen instead.
     */
    fun isAppManaged(item: BrowserItem, rootKey: String?): Boolean {
        val root = rootKey?.trimEnd('/') ?: return false
        val key = item.key.trimEnd('/')
        if (!root.startsWith("content://")) {
            val steward = "$root/PocketSteward"
            return key == steward || key == "$steward/Trash" || key.startsWith("$steward/Trash/")
        }
        val parent = item.parentRef?.rawValue()?.trimEnd('/')
        if (item.isDirectory && item.name == "PocketSteward" && parent == root) return true
        // Document IDs of the external-storage provider spell out the path.
        return safPathHasTrash(item.key, includeFolderItself = true)
    }

    private fun safPathHasTrash(uri: String, includeFolderItself: Boolean): Boolean {
        val segments = com.pocketsteward.app.plan.PlanTree.safSegments(uri)
        for (i in 0 until segments.size - 1) {
            if (segments[i] == "PocketSteward" && segments[i + 1] == "Trash") {
                return includeFolderItself || segments.size > i + 2
            }
        }
        return false
    }

    /** True when a paste into [destination] would land inside the app's Trash. */
    fun isInsideTrash(destination: String, rootKey: String?): Boolean {
        val root = rootKey?.trimEnd('/') ?: return false
        if (root.startsWith("content://")) return safPathHasTrash(destination, includeFolderItself = true)
        val d = destination.trimEnd('/')
        val trash = "$root/PocketSteward/Trash"
        return d == trash || d.startsWith("$trash/")
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase()
    }

    /** "file2" before "file10", case-insensitive: the order people expect. */
    object NaturalOrder : Comparator<String> {
        private val chunk = Regex("""\d+|\D+""")
        override fun compare(a: String, b: String): Int {
            val xa = chunk.findAll(a.lowercase()).map { it.value }.toList()
            val xb = chunk.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(xa.size, xb.size)) {
                val p = xa[i]
                val q = xb[i]
                val c = if (p[0] in '0'..'9' && q[0] in '0'..'9') {
                    val np = p.trimStart('0')
                    val nq = q.trimStart('0')
                    if (np.length != nq.length) np.length - nq.length else np.compareTo(nq)
                } else {
                    p.compareTo(q)
                }
                if (c != 0) return c
            }
            return xa.size - xb.size
        }
    }
}
