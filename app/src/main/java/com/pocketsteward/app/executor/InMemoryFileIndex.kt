package com.pocketsteward.app.executor

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue

/**
 * [FileIndex] over a snapshot of Room rows, not a live adapter — the
 * executor loads every [FileRecord] under a scope root once
 * ([com.pocketsteward.app.data.db.FileRecordDao.getAllUnderScopeRoot]) and
 * builds one of these, so [com.pocketsteward.app.plan.PlanValidator] can run
 * its checks synchronously against a stable view instead of issuing a
 * database query per rule per operation.
 *
 * Deliberately not in the `plan` package: `plan` stays free of any Room
 * dependency so it can be verified in a plain-Kotlin module without the
 * Android SDK (see PlanValidatorTest); this adapter is the Android-side
 * translation layer and isn't held to that bar.
 */
class InMemoryFileIndex(records: List<FileRecord>) : FileIndex {
    private val byStableRef: Map<String, FileRecord> = records.associateBy { it.stableRef }

    // Grouped, not associateBy: two records can share a (parent, lowercased
    // name) key on a case-sensitive filesystem — e.g. "report.txt" and
    // "Report.TXT" coexisting — and associateBy would silently drop one,
    // hiding exactly the collision this index exists to catch.
    private val byParentAndLowerName: Map<String, List<FileRecord>> =
        records.groupBy { collisionKey(it.parentRef.orEmpty(), it.displayName) }

    override fun exists(ref: FileRef): Boolean = when (ref) {
        is FileRef.Child -> caseInsensitiveMatch(ref.parent, ref.name, excluding = null) != null
        else -> byStableRef.containsKey(ref.rawValue())
    }

    override fun isDirectory(ref: FileRef): Boolean = when (ref) {
        is FileRef.Child -> {
            val match = caseInsensitiveMatch(ref.parent, ref.name, excluding = null)
            match?.let { byStableRef[it.rawValue()]?.isDirectory } == true
        }
        else -> byStableRef[ref.rawValue()]?.isDirectory == true
    }

    override fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef?): FileRef? {
        val candidates = byParentAndLowerName[collisionKey(directory.rawValue(), name)] ?: return null
        val excludingRaw = excluding?.rawValue()
        val match = candidates.firstOrNull { it.stableRef != excludingRaw } ?: return null
        return parseFileRef(match.stableRef)
    }

    private fun collisionKey(parentRawValue: String, name: String) = "$parentRawValue\u0000${name.lowercase()}"
}
