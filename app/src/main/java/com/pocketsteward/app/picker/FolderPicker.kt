package com.pocketsteward.app.picker

/**
 * One folder as the picker needs to judge it. Plain values rather than a
 * `FileRef` plus a `FileRecord` so the search / sort / filter decisions stay
 * pure Kotlin and get real test coverage, the same boundary `SortScope`,
 * `KeeperSelector` and `TaskManifest` are held to.
 */
data class PickerFolder(
    val path: String,
    val displayName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val modifiedAt: Long?,
    val isProtected: Boolean,
)

enum class FolderSort(val label: String) {
    NAME("Name"),
    FILE_COUNT("File count"),
    SIZE("Size"),
    MODIFIED("Date modified"),
}

/**
 * Spec 2c. All three default to off: a picker that silently hides folders the
 * user can see in any other file manager is worse than a long list, because
 * the folder they are looking for is simply absent with no explanation.
 */
data class FolderFilters(
    val hideEmpty: Boolean = false,
    val onlyProtected: Boolean = false,
    val hideSystem: Boolean = false,
)

/**
 * Folder names that are the operating system's business rather than the
 * user's. `Android/` holds per-app data that moving would break, and a
 * leading dot is the long-standing convention for "not shown by default".
 */
fun isSystemFolder(displayName: String): Boolean =
    displayName.startsWith(".") || displayName.equals("Android", ignoreCase = true)

object FolderPicker {

    /**
     * Spec 2a/2b/2c in one pass, in the order that matters: filter, then
     * search, then sort. Sorting first would be work thrown away, and
     * searching before filtering would let a hidden folder match a query and
     * then vanish, which reads as a bug.
     *
     * [query] is a case-insensitive substring match on the display name.
     * Blank matches everything rather than nothing.
     */
    fun apply(
        folders: List<PickerFolder>,
        query: String = "",
        sort: FolderSort = FolderSort.NAME,
        filters: FolderFilters = FolderFilters(),
    ): List<PickerFolder> {
        val trimmedQuery = query.trim()
        return folders
            .asSequence()
            .filterNot { filters.hideEmpty && it.fileCount == 0 }
            .filterNot { filters.onlyProtected && !it.isProtected }
            .filterNot { filters.hideSystem && isSystemFolder(it.displayName) }
            .filter { trimmedQuery.isEmpty() || it.displayName.contains(trimmedQuery, ignoreCase = true) }
            .sortedWith(comparatorFor(sort))
            .toList()
    }

    /**
     * Name is ascending; everything else is descending, because "sort by
     * size" means "show me the big ones" and a picker that answered it with
     * the smallest folder first would be technically correct and useless.
     * Every comparator falls back to name so the order is total — two folders
     * with the same file count must not swap places between compositions.
     */
    private fun comparatorFor(sort: FolderSort): Comparator<PickerFolder> {
        val byName = compareBy<PickerFolder> { it.displayName.lowercase() }.thenBy { it.path }
        return when (sort) {
            FolderSort.NAME -> byName
            FolderSort.FILE_COUNT -> compareByDescending<PickerFolder> { it.fileCount }.then(byName)
            FolderSort.SIZE -> compareByDescending<PickerFolder> { it.totalBytes }.then(byName)
            // A folder with no known timestamp sorts last rather than first:
            // unknown is not the same as new.
            FolderSort.MODIFIED -> compareByDescending<PickerFolder> { it.modifiedAt ?: Long.MIN_VALUE }.then(byName)
        }
    }
}

/**
 * Spec 2d. Most recent first, capped, no duplicates.
 *
 * Stored in DataStore rather than Room, deliberately: `AppDatabase` is on
 * `fallbackToDestructiveMigration` and holds undo journals for runs of several
 * thousand operations, so a new entity would destroy them. `SettingsRepository`
 * already serialises a list into one preference string for project keywords;
 * this follows that pattern exactly.
 */
object RecentFolders {
    const val MAX: Int = 5

    /** [path] moves to the front, whether or not it was already in [existing]. */
    fun add(existing: List<String>, path: String, max: Int = MAX): List<String> {
        val normalized = path.trimEnd('/').ifBlank { path }
        if (normalized.isBlank()) return existing
        return (listOf(normalized) + existing.filterNot { it.trimEnd('/') == normalized })
            .take(max)
    }

    /**
     * Newline-delimited, the same encoding `SettingsRepository` uses for
     * project keywords. A path cannot contain a newline on any filesystem
     * this app reaches, so nothing needs escaping.
     */
    fun encode(paths: List<String>): String = paths.joinToString("\n")

    fun decode(raw: String?): List<String> =
        (raw ?: "").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
}
