package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRef

/**
 * Everything [PlanValidator] needs to know about the current state of
 * indexed storage, without depending on Room directly — kept pure so the
 * validator can be unit tested with a fake in a plain JVM test, no Android
 * SDK required. The real app satisfies this from the scan index
 * (`RoomFileIndex`).
 */
interface FileIndex {
    fun exists(ref: FileRef): Boolean
    fun isDirectory(ref: FileRef): Boolean

    /**
     * The existing entry directly under [directory] named [name], matched
     * case-insensitively and never equal to [excluding], or null if there
     * isn't one (Section 12's case-insensitive collision check — this is
     * what catches "Report.TXT" already existing when renaming
     * "report.txt" to it, on a case-sensitive filesystem where a plain
     * [exists] on the exact destination path wouldn't necessarily overlap
     * with the source at all). [excluding] matters for a rename: the
     * source itself often case-insensitively matches its own new name
     * (e.g. a pure case-normalizing rename) and must not count as its own
     * collision. Only meaningful where a ref decomposes into (parent,
     * name) — always null for [FileRef.Saf] for now, since SAF document
     * URIs don't expose a reliable path decomposition; real collision
     * detection for SAF destinations is limited to [exists] until that's
     * solved.
     */
    fun caseInsensitiveMatch(directory: FileRef, name: String, excluding: FileRef? = null): FileRef?
}
