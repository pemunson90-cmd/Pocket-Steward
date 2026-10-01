package com.pocketsteward.app.library

/** Direct inventory slices are derived metadata, never destination authorization. */
object DirectoryRefreshPolicy {
    const val MAX_DIRECTORIES = 64
    const val MAX_PATH_BYTES = 6_000

    fun select(libraryRoot: String, directories: List<String>, canonicalRoot: String = libraryRoot): List<String>? {
        val root = libraryRoot.trimEnd('/')
        if (!safeAbsolute(root) || directories.isEmpty() || directories.size > MAX_DIRECTORIES) return null
        val alias = canonicalRoot.trimEnd('/')
        if (!safeAbsolute(alias)) return null
        val normalized = directories.map { raw ->
            val path = raw.trimEnd('/')
            if (alias != root && path.startsWith("$alias/")) root + path.removePrefix(alias) else path
        }.distinct()
        if (normalized.any { !safeAbsolute(it) || it == root || !it.startsWith("$root/") }) return null
        if (normalized.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_PATH_BYTES) return null
        val ordered = normalized.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })
        val result = mutableListOf<String>()
        for (path in ordered) if (result.none { path.startsWith("$it/") }) result += path
        return result
    }

    fun overlappingScopes(folder: String, knownScopes: List<String>): List<String> = knownScopes.filter { scope ->
        safeAbsolute(scope.trimEnd('/')) && (LibraryPolicy.canAdopt(scope, folder) || LibraryPolicy.canAdopt(folder, scope))
    }.distinct()

    private fun safeAbsolute(path: String): Boolean = path.startsWith('/') && path.length > 1 &&
        path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it }
}
