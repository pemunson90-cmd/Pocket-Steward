package com.pocketsteward.app.provider

import java.util.Base64

/**
 * Pure rules for what Pocket Steward's picker source exposes. No Android types,
 * so plain JVM tests cover them.
 *
 * Paths here are relative to shared storage (for example "Download/a.pdf"),
 * always with '/' separators.
 */
object PickerPaths {
    const val VIRTUAL_ROOT = "v:root"
    const val VIRTUAL_RECENT = "v:recent"
    const val FILE_PREFIX = "fs:"

    /** Legacy ids from 1.3.x, relative to the PocketSteward/ folder. */
    const val LEGACY_PREFIX = "ps:"
    const val LEGACY_ROOT = "ps-root"
    const val MANAGED_DIR = "PocketSteward"

    /** Top-level shortcuts shown under the root, in this order, when the folder exists. */
    val SHORTCUTS: List<Pair<String, String>> = listOf(
        "Download" to "Downloads",
        "Documents" to "Documents",
        "Pictures" to "Pictures",
        "DCIM" to "Camera",
    )

    fun idFor(relativePath: String): String {
        val clean = normalize(relativePath)
        return FILE_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(clean.toByteArray(Charsets.UTF_8))
    }

    /** Relative path for a file id, or null for virtual/unknown ids. Legacy ids map into PocketSteward/. */
    fun relativeFor(documentId: String): String? = when {
        documentId == LEGACY_ROOT -> MANAGED_DIR
        documentId.startsWith(FILE_PREFIX) -> decode(documentId.removePrefix(FILE_PREFIX))
        documentId.startsWith(LEGACY_PREFIX) ->
            decode(documentId.removePrefix(LEGACY_PREFIX))?.let { normalize("$MANAGED_DIR/$it") }
        else -> null
    }

    /**
     * Rejects anything that must never be offered: path traversal, hidden files
     * and folders, app-private Android/ trees, and Pocket Steward's Trash.
     */
    fun isPickable(relativePath: String): Boolean {
        val clean = normalize(relativePath)
        if (clean.isEmpty()) return true
        val parts = clean.split('/')
        if (parts.any { it == ".." || it == "." }) return false
        if (parts.any { it.startsWith(".") }) return false
        if (parts.first().equals("Android", ignoreCase = true)) return false
        if (parts.size >= 2 && parts[0] == MANAGED_DIR && parts[1].equals("Trash", ignoreCase = true)) return false
        return true
    }

    data class Entry(val name: String, val isDirectory: Boolean, val lastModified: Long)

    /** Folders first (A to Z), then files newest first: what you want when picking. */
    fun <T> sortForPicking(items: List<T>, entry: (T) -> Entry): List<T> =
        items.sortedWith(
            compareBy<T>({ !entry(it).isDirectory })
                .thenComparator { a, b ->
                    val ea = entry(a); val eb = entry(b)
                    if (ea.isDirectory) ea.name.lowercase().compareTo(eb.name.lowercase())
                    else eb.lastModified.compareTo(ea.lastModified)
                },
        )

    fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")

    private fun decode(encoded: String): String? = runCatching {
        normalize(String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
    }.getOrNull()
}
