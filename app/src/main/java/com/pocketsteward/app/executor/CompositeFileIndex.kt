package com.pocketsteward.app.executor

import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.storage.FileRef

/**
 * Union of independently authorized index snapshots.
 *
 * Cross-root plans use this rather than weakening PlanValidator. Source scan
 * roots remain one snapshot and each user-approved destination root is a live
 * one-folder snapshot. The validator therefore sees exactly the roots the user
 * authorized, and nothing else.
 */
class CompositeFileIndex(
    private val delegates: List<FileIndex>,
) : FileIndex {
    init {
        require(delegates.isNotEmpty()) { "CompositeFileIndex needs at least one delegate." }
    }

    override fun exists(ref: FileRef): Boolean =
        delegates.any { it.exists(ref) }

    override fun isDirectory(ref: FileRef): Boolean =
        delegates.any { it.exists(ref) && it.isDirectory(ref) }

    override fun parentOf(ref: FileRef): FileRef? =
        delegates.firstNotNullOfOrNull { delegate ->
            if (delegate.exists(ref)) delegate.parentOf(ref) else null
        }

    override fun caseInsensitiveMatch(
        directory: FileRef,
        name: String,
        excluding: FileRef?,
    ): FileRef? =
        delegates.firstNotNullOfOrNull { it.caseInsensitiveMatch(directory, name, excluding) }
}
