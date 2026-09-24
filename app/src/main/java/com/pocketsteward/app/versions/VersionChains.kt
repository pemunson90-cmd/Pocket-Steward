package com.pocketsteward.app.versions

import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.cleanup.SortCandidate
import com.pocketsteward.app.cleanup.SortScope
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.parseFileRef

/**
 * Finds files that are versions of each other ("Resume.pdf", "Resume (1).pdf",
 * "Resume_v3 final.pdf") and proposes moving every version except the newest
 * into an "Older versions" folder beside them.
 *
 * Deliberately conservative, because a wrong chain moves a file someone
 * needs out of sight:
 * - Only explicit version markers are stripped: copy numbers, "copy",
 *   v2 / ver 3 / rev 4, and final / draft / edited and similar words.
 *   Dates and plain numbers are kept, so "Statement 2026-05" and
 *   "Statement 2026-06", or "Invoice 1043" and "Invoice 1044", stay separate
 *   documents.
 * - Members must share a folder and an extension.
 * - Protected folders (the do-not-sort marker) and files already inside an
 *   "Older versions" folder are left alone, so running it twice proposes
 *   nothing new.
 *
 * Pure Kotlin, no Android imports, unit tested. It only builds operations;
 * they still go through PlanValidator, the preview, the executor and the
 * journal like every other plan, and "moving" here is an ordinary,
 * undoable move.
 */
object VersionChains {

    const val OLDER_VERSIONS_FOLDER = "Older versions"

    data class Chain(
        /** Normalized name shared by every member, for display ("resume"). */
        val familyName: String,
        val parentRef: String,
        /** Newest first. [latest] is the one that stays. */
        val members: List<FileRecord>,
    ) {
        val latest: FileRecord get() = members.first()
        val older: List<FileRecord> get() = members.drop(1)
    }

    // Optional separator for bracketed copy numbers ("Resume(1)"); a required
    // one for word markers, so "Threshold" keeps its "old" and "Renew" its "new".
    private val OPT = """[\s._\-]*"""
    private val REQ = """[\s._\-]+"""
    private val trailingMarkers = listOf(
        Regex("""$OPT\(\d{1,3}\)$"""), // "name (1)"
        Regex("""$OPT\[\d{1,3}\]$"""), // "name [2]"
        Regex("""${REQ}copy(${OPT}\d{1,3})?$"""), // "name - Copy", "name copy 2"
        Regex("""${REQ}(v|ver|version|rev|revision)\.?\s?\d{1,3}([._]\d{1,3}){0,2}$"""), // "_v2", " v1.3", "rev 4"
        Regex("""$REQ(final|draft|edited|edit|revised|updated|latest|old|new|wip|backup|bak)\d{0,2}$"""),
    )
    private val leadingMarkers = listOf(
        Regex("""^copy\s+of\s+"""), // "Copy of name"
    )

    /** Lower-cased name with every version marker removed, or null when nothing meaningful is left. */
    fun familyKey(stem: String): String? {
        var s = stem.trim().lowercase()
        leadingMarkers.forEach { s = it.replace(s, "") }
        // Markers stack ("Resume_v2 final (1)"), so strip until stable.
        var changed = true
        var guard = 0
        while (changed && guard++ < 12) {
            changed = false
            for (marker in trailingMarkers) {
                val next = marker.replace(s, "")
                if (next != s && next.isNotBlank()) {
                    s = next
                    changed = true
                }
            }
        }
        s = s.trim(' ', '.', '_', '-')
        return s.takeIf { it.length >= 2 }
    }

    /** Explicit version number when the name carries one (v3, rev 4, (2)), for ordering ties. */
    internal fun versionNumber(stem: String): Int {
        val lower = stem.lowercase()
        Regex("""(?:v|ver|version|rev|revision)\.?\s?(\d{1,3})""").findAll(lower).lastOrNull()
            ?.let { return it.groupValues[1].toInt() }
        Regex("""\((\d{1,3})\)$""").find(lower.trim())?.let { return it.groupValues[1].toInt() }
        return 0
    }

    private fun stemOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun detect(records: List<FileRecord>): List<Chain> {
        val protectedFolders = SortScope.protectedFolders(
            records.map { SortCandidate(it.stableRef, it.parentRef, it.displayName, it.isDirectory) },
        )
        return records.asSequence()
            .filter { !it.isDirectory && it.parentRef != null }
            .filter { it.displayName != DO_NOT_SORT_MARKER }
            .filter { parentName(it.parentRef!!) != OLDER_VERSIONS_FOLDER.lowercase() }
            .filter {
                !SortScope.isProtected(
                    SortCandidate(it.stableRef, it.parentRef, it.displayName, false),
                    protectedFolders,
                )
            }
            .mapNotNull { record ->
                familyKey(stemOf(record.displayName))?.let { key ->
                    Triple(record.parentRef!!.trimEnd('/'), record.extension.lowercase(), key) to record
                }
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }
            .map { (group, members) ->
                Chain(
                    familyName = group.third,
                    parentRef = group.first,
                    members = members.sortedWith(
                        compareByDescending<FileRecord> { it.modifiedAt ?: Long.MIN_VALUE }
                            .thenByDescending { versionNumber(stemOf(it.displayName)) }
                            .thenBy { it.displayName.length }
                            .thenBy { it.displayName },
                    ),
                )
            }
            .sortedWith(compareBy<Chain> { it.parentRef.lowercase() }.thenBy { it.familyName })
    }

    private fun parentName(parentRef: String): String =
        parentRef.trimEnd('/').substringAfterLast('/').substringAfterLast("%2F").lowercase()

    /**
     * CreateDirectory (only when the folder is not already there) plus one
     * Move per older version. Reuses an existing "Older versions" folder's
     * real reference, the same way semantic grouping does.
     */
    fun planOperations(chains: List<Chain>, records: List<FileRecord>): List<PlannedOperation> {
        val operations = mutableListOf<PlannedOperation>()
        val created = mutableSetOf<String>()
        for (chain in chains) {
            if (chain.older.isEmpty()) continue
            val parent = parseFileRef(chain.parentRef)
            val existing = records.firstOrNull {
                it.isDirectory &&
                    it.parentRef?.trimEnd('/') == chain.parentRef &&
                    it.displayName.equals(OLDER_VERSIONS_FOLDER, ignoreCase = true)
            }
            val folder: FileRef = existing?.stableRef?.let(::parseFileRef) ?: parent.child(OLDER_VERSIONS_FOLDER)
            if (existing == null && created.add(chain.parentRef)) {
                operations += PlannedOperation.CreateDirectory(
                    parent = parent,
                    name = OLDER_VERSIONS_FOLDER,
                    reason = "Holds earlier versions so the newest one stands alone.",
                )
            }
            for (old in chain.older) {
                operations += PlannedOperation.Move(
                    source = parseFileRef(old.stableRef),
                    destination = folder.child(old.displayName),
                    reason = "Older version of ${chain.latest.displayName}, which stays where it is.",
                )
            }
        }
        return operations
    }
}
