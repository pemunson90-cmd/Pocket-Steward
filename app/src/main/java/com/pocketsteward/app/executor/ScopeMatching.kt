package com.pocketsteward.app.executor

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.StorageGateway
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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

/** Provider scope membership is observed through real parent routes, never a URI prefix. */
internal suspend fun matchingStorageScopes(gateway: StorageGateway, ref: FileRef, knownScopes: List<String>, taskScope: String): Set<String> {
    val result = linkedSetOf<String>()
    for (scope in knownScopes.distinct()) {
        currentCoroutineContext().ensureActive()
        when (gateway.containsInScope(ref, scope)) {
            true -> result += scope
            false -> Unit
            null -> {
                // Compatibility for gateways that expose no provider route;
                // the production SAF gateway always supplies a live result.
                if (scope in matchingScopeRoots(ref, listOf(scope)) || ref !is FileRef.Direct && scope == taskScope) result += scope
            }
        }
    }
    return result
}
