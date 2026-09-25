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
    private val rootKey = root.rawValue().trimEnd('/')
    private val byKey = entries.associateBy { it.ref.rawValue().trimEnd('/') }
    private val byParent = entries.groupBy { it.parentRef.rawValue().trimEnd('/') }

    override fun exists(ref: FileRef): Boolean {
        val key = ref.rawValue().trimEnd('/')
        return key == rootKey || key in byKey
    }

    override fun isDirectory(ref: FileRef): Boolean {
        val key = ref.rawValue().trimEnd('/')
        return key == rootKey || byKey[key]?.isDirectory == true
    }

    override fun parentOf(ref: FileRef): FileRef? {
        val key = ref.rawValue().trimEnd('/')
        if (key == rootKey) return null
        return byKey[key]?.parentRef
    }

    override fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef?): FileRef? {
        val parentKey = directory.rawValue().trimEnd('/')
        return byParent[parentKey]
            .orEmpty()
            .firstOrNull { entry ->
                entry.ref != excluding && entry.displayName.equals(name, ignoreCase = true)
            }
            ?.ref
    }
}