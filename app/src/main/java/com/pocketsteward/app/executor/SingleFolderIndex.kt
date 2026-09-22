package com.pocketsteward.app.executor

import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue

/**
 * A [FileIndex] over one directory read live from the gateway, rather than
 * over the scan index.
 *
 * Milestone 6's folder browser (spec 6c) reaches folders that have never
 * been scanned, so `InMemoryFileIndex` built from `getAllUnderScopeRoot`
 * would be empty for them and `PlanValidator` would reject every operation
 * with "Parent directory does not exist in the index" — which is correct
 * behaviour for a validator that only trusts what it has been told, and the
 * wrong answer here.
 *
 * Scope is deliberately one directory deep: the operations it exists to
 * validate (write a protection marker, trash one) both act on a named child
 * of a known folder, and an index that claimed to know more than it had
 * actually listed would be lying to the validator.
 */
class SingleFolderIndex(
    private val directory: FileRef,
    children: List<FileEntry>,
) : FileIndex {
    private val directoryKey = directory.rawValue().trimEnd('/')
    private val byKey: Map<String, FileEntry> = children.associateBy { it.ref.rawValue() }

    override fun exists(ref: FileRef): Boolean = when (ref) {
        is FileRef.Child ->
            ref.parent.rawValue().trimEnd('/') == directoryKey &&
                caseInsensitiveMatch(ref.parent, ref.name, excluding = null) != null
        else -> ref.rawValue().trimEnd('/') == directoryKey || ref.rawValue() in byKey
    }

    override fun isDirectory(ref: FileRef): Boolean = when (ref) {
        is FileRef.Child -> {
            val match = caseInsensitiveMatch(ref.parent, ref.name, excluding = null)
            match?.let { byKey[it.rawValue()]?.isDirectory } == true
        }
        else -> when {
            ref.rawValue().trimEnd('/') == directoryKey -> true
            else -> byKey[ref.rawValue()]?.isDirectory == true
        }
    }

    override fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef?): FileRef? {
        if (directory.rawValue().trimEnd('/') != directoryKey) return null
        return byKey.values
            .firstOrNull { it.ref != excluding && it.displayName.equals(name, ignoreCase = true) }
            ?.ref
    }
}
