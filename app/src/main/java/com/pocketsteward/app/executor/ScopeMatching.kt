package com.pocketsteward.app.executor

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue

/**
 * Which of [knownScopes] contain [ref], by path.
 *
 * One definition shared by forward execution and undo. It used to be two
 * identical private copies in PlanExecutor and UndoExecutor; if one were ever
 * fixed and the other not, a move and its undo would disagree about which
 * scans a file belongs to, and the file would quietly drop out of a scope's
 * results after being put back.
 *
 * Segment-aware: `/0/Download2` is not inside `/0/Download`. A scope is only a
 * parent when the path continues past it with a `/`. Trailing slashes on
 * either side are ignored, and duplicate scopes collapse.
 */
internal fun matchingScopeRoots(ref: FileRef, knownScopes: List<String>): List<String> {
    val raw = ref.rawValue().trimEnd('/')
    return knownScopes.distinct().filter { scope ->
        val normalized = scope.trimEnd('/')
        raw == normalized || raw.startsWith("$normalized/")
    }
}
