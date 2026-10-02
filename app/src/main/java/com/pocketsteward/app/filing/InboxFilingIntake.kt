package com.pocketsteward.app.filing

import com.pocketsteward.app.data.db.FileRecord

/** Selects reviewed source units by indexed parent identity, including SAF URIs. */
object InboxFilingIntake {
    data class Selection(
        val records: List<FileRecord>,
        val retainedUncertainSourceRefs: Set<String>,
        val indexedFolderDescendantCount: Int = 0,
    )

    fun select(
        records: List<FileRecord>,
        inboxRootRefs: Set<String>,
        checkpointOnly: Boolean,
        includeDirectories: Boolean,
        checkpointRootRefs: Set<String> = emptySet(),
    ): Selection {
        val roots = inboxRootRefs.mapTo(hashSetOf()) { it.trimEnd('/') }
        val checkpointRoots = records.asSequence()
            .filter { it.isDirectory && it.displayName.equals("Uncertain", ignoreCase = true) }
            .filter { it.parentRef?.trimEnd('/') in roots }
            .map { it.stableRef.trimEnd('/') }
            .toHashSet()
        checkpointRoots += checkpointRootRefs.map { it.trimEnd('/') }.filter { it in roots }
        checkpointRoots += records.filter { it.isDirectory && it.displayName.equals("Uncertain", true) && it.stableRef.trimEnd('/') in roots }.map { it.stableRef.trimEnd('/') }
        val sourceRoots = if (checkpointOnly) checkpointRoots else roots
        val candidates = records.filter { record ->
            record.parentRef?.trimEnd('/') in sourceRoots &&
                (includeDirectories || !record.isDirectory) &&
                // The checkpoint itself is never proposed as a project or move unit.
                record.stableRef.trimEnd('/') !in checkpointRoots
        }.distinctBy { it.stableRef }
        val parents = records.associate { it.stableRef.trimEnd('/') to it.parentRef?.trimEnd('/') }
        val candidateDirectories = candidates.filter { it.isDirectory }.mapTo(hashSetOf()) { it.stableRef.trimEnd('/') }
        val ancestorCache = hashMapOf<String, Boolean>()
        fun insideReviewedFolder(record: FileRecord): Boolean {
            var parent = record.parentRef?.trimEnd('/')
            val visited = hashSetOf<String>()
            var inside = false
            while (parent != null && visited.add(parent)) {
                if (parent in candidateDirectories) { inside = true; break }
                if (parent in ancestorCache) { inside = ancestorCache.getValue(parent); break }
                parent = parents[parent]
            }
            visited.forEach { ancestorCache[it] = inside }
            return inside
        }
        // Overlapping scopes must not plan both an intact folder and its children.
        val selected = candidates.filterNot(::insideReviewedFolder)
        val selectedRefs = selected.mapTo(hashSetOf()) { it.stableRef }
        val descendantCount = records.distinctBy { it.stableRef }.count {
            it.stableRef !in selectedRefs && insideReviewedFolder(it)
        }
        return Selection(
            selected,
            selected.asSequence()
                .filter { it.parentRef?.trimEnd('/') in checkpointRoots }
                .map { it.stableRef }
                .toSet(),
            descendantCount,
        )
    }
}
