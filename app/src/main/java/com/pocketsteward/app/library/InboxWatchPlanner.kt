package com.pocketsteward.app.library

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

data class InboxWatchCoverage(val paths: List<String>, val limited: Boolean)

/** Bounded, read-only enumeration for nonrecursive Android observers. */
object InboxWatchPlanner {
    const val MAX_FOLDERS = 512
    const val MAX_ENTRIES = 100_000
    fun plan(storageRoot: String, roots: List<String>, maxFolders: Int = MAX_FOLDERS,
        maxEntries: Int = MAX_ENTRIES, excluded: (String) -> Boolean = { false }): InboxWatchCoverage {
        require(maxFolders > 0 && maxEntries > 0)
        val storage = File(storageRoot).canonicalPath
        val paths = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        var limited = roots.distinct().size > InboxObservationPolicy.MAX_ROOTS
        var entries = 0
        fun admit(file: File) {
            try {
                if (Files.isSymbolicLink(file.toPath())) return
                val path = file.canonicalPath
                if (!InboxObservationPolicy.isWithinStorage(storage, path) || excluded(path) || !file.isDirectory || !file.canRead() || path in paths) return
                if (paths.size >= maxFolders) { limited = true; return }
                paths += path; queue.addLast(path)
            } catch (_: Exception) { limited = true }
        }
        val selectedRoots = roots.distinct().take(InboxObservationPolicy.MAX_ROOTS)
        selectedRoots.forEach { admit(File(it)) }
        // Keep every configured landing folder and checkpoint ahead of deeper trees.
        paths.toList().forEach { raw ->
            val root = File(raw)
            if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return@forEach
            try { Files.newDirectoryStream(root.toPath()).use { children ->
                for (child in children) {
                    if (++entries > maxEntries) { limited = true; break }
                    if (child.fileName.toString().equals("Uncertain", true)) admit(child.toFile())
                }
            } } catch (_: Exception) { limited = true }
        }
        while (queue.isNotEmpty() && entries <= maxEntries) {
            val parent = queue.removeFirst()
            try { Files.newDirectoryStream(File(parent).toPath()).use { children ->
                for (child in children) {
                    if (++entries > maxEntries) { limited = true; break }
                    admit(child.toFile())
                    if (paths.size >= maxFolders) { limited = true; break }
                }
            } } catch (_: Exception) { limited = true }
            if (paths.size >= maxFolders) { limited = true; break }
        }
        return InboxWatchCoverage(paths.toList(), limited || queue.isNotEmpty())
    }
}
