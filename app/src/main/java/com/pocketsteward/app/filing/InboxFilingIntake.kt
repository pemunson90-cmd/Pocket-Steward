package com.pocketsteward.app.filing

import com.pocketsteward.app.data.db.FileRecord

/** Selects reviewed source units by indexed parent identity, including SAF URIs. */
object InboxFilingIntake {
    data class Selection(
        val records: List<FileRecord>,
        val retainedUncertainSourceRefs: Set<String>,
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
        val selected = records.filter { record ->
            record.parentRef?.trimEnd('/') in sourceRoots &&
                (includeDirectories || !record.isDirectory) &&
                // The checkpoint itself is never proposed as a project or move unit.
                record.stableRef.trimEnd('/') !in checkpointRoots
        }.distinctBy { it.stableRef }
        return Selection(
            selected,
            selected.asSequence()
                .filter { it.parentRef?.trimEnd('/') in checkpointRoots }
                .map { it.stableRef }
                .toSet(),
        )
    }
}
