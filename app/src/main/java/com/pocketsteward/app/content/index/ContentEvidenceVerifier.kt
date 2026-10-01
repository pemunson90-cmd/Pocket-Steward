package com.pocketsteward.app.content.index

import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ContentEvidenceSnapshot(val stableRef: String, val name: String, val size: Long?, val modifiedAt: Long?, val quickFingerprint: String? = null)

/** Live metadata and optional bounded cache sample before cached excerpts become search results, answers or project evidence. */
class ContentEvidenceVerifier(
    private val observeFingerprint: (suspend (ContentEvidenceSnapshot) -> String)? = null,
    private val observe: suspend (String) -> FileMetadata,
) {
    suspend fun currentRefs(snapshots: List<ContentEvidenceSnapshot>): Set<String> {
        val current = hashSetOf<String>()
        for ((ref, rows) in snapshots.groupBy { it.stableRef }) {
            currentCoroutineContext().ensureActive()
            if (rows.any { it.size == null || it.modifiedAt == null }) continue
            try {
                val live = observe(ref)
                if (!live.isDirectory && live.ref.rawValue() == ref && rows.all { row ->
                        row.name == live.displayName && row.size == live.sizeBytes && row.modifiedAt == live.modifiedAtEpochMs
                    }) {
                    if (observeFingerprint != null) {
                        if (rows.any { it.quickFingerprint?.startsWith(com.pocketsteward.app.evidence.EvidenceFingerprint.PREFIX) != true }) continue
                        val sampled = observeFingerprint.invoke(rows.first())
                        if (rows.any { it.quickFingerprint != sampled }) continue
                        val after = observe(ref)
                        if (after.isDirectory || after.ref.rawValue() != ref || rows.any { it.name != after.displayName || it.size != after.sizeBytes || it.modifiedAt != after.modifiedAtEpochMs }) continue
                    }
                    current += ref
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Revoked, missing or changed sources cannot supply current evidence. */ }
        }
        return current
    }
}
