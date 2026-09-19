package com.pocketsteward.app.scan

/**
 * Canonicalizes a set of direct-storage scan roots before any filesystem walk.
 *
 * If one selected root contains another selected root, only the ancestor is
 * kept. That prevents double-walking and double-counting while preserving
 * unrelated sibling roots.
 */
object ScanRootSet {
    fun normalize(paths: Collection<String>): List<String> {
        val cleaned = paths
            .asSequence()
            .map(::normalizePath)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String>({ depth(it) }, { it }))

        val kept = mutableListOf<String>()
        for (candidate in cleaned) {
            if (kept.none { ancestor -> candidate == ancestor || isDescendant(candidate, ancestor) }) {
                kept += candidate
            }
        }
        return kept
    }

    fun isDescendant(candidate: String, ancestor: String): Boolean {
        val child = normalizePath(candidate)
        val parent = normalizePath(ancestor)
        if (child == parent || parent.isBlank()) return false
        val prefix = if (parent == "/") "/" else "$parent/"
        return child.startsWith(prefix)
    }

    private fun normalizePath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "/") return "/"
        return trimmed.trimEnd('/')
    }

    private fun depth(path: String): Int =
        normalizePath(path).split('/').count { it.isNotBlank() }
}
