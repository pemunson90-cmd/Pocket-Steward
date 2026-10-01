package com.pocketsteward.app.executor

import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue

/**
 * A bounded live directory-tree snapshot used to validate cross-root plans.
 * Unlike [SingleFolderIndex], this knows existing children below project/version
 * folders, so a reviewed filing plan can catch name collisions before Run.
 */
class LiveTreeFileIndex(
    private val root: FileRef,
    entries: List<FileEntry>,
) : FileIndex {
    private val rootKey = key(root)
    private val byKey = entries.associateBy { key(it.ref) }
    private val byParent = entries
        .mapNotNull { entry ->
            entry.parentRef?.let(::key)?.let { parent -> parent to entry }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    override fun exists(ref: FileRef): Boolean {
        val key = key(ref)
        return key == rootKey || key in byKey
    }

    override fun isDirectory(ref: FileRef): Boolean {
        val key = key(ref)
        return key == rootKey || byKey[key]?.isDirectory == true
    }

    override fun parentOf(ref: FileRef): FileRef? {
        val key = key(ref)
        if (key == rootKey) return null
        return byKey[key]?.parentRef
    }

    override fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef?): FileRef? {
        val parentKey = key(directory)
        return byParent[parentKey]
            .orEmpty()
            .firstOrNull { entry ->
                entry.ref != excluding && entry.displayName.equals(name, ignoreCase = true)
            }
            ?.ref
    }
    private fun key(ref: FileRef): String = when (ref) {
        is FileRef.Direct -> ref.absolutePath.trimEnd('/').lowercase(java.util.Locale.ROOT)
        is FileRef.Saf -> ref.documentUri
        is FileRef.Child -> caseInsensitiveMatch(ref.parent, ref.name, null)?.let(::key) ?: ref.rawValue()
    }

}