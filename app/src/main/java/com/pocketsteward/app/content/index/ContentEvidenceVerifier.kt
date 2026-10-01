package com.pocketsteward.app.content.index

import com.pocketsteward.app.storage.FileMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ContentEvidenceSnapshot(val stableRef: String, val name: String, val size: Long?, val modifiedAt: Long?)

/** Metadata-only proof before cached excerpts become search results, answers or project evidence. */
class ContentEvidenceVerifier(private val observe: suspend (String) -> FileMetadata) {
    suspend fun currentRefs(snapshots: List<ContentEvidenceSnapshot>): Set<String> {
        val current = hashSetOf<String>()
        for ((ref, rows) in snapshots.groupBy { it.stableRef }) {
            currentCoroutineContext().ensureActive()
            if (rows.any { it.size == null || it.modifiedAt == null }) continue
            try {
                val live = observe(ref)
                if (!live.isDirectory && rows.all { row ->
                        row.name == live.displayName && row.size == live.sizeBytes && row.modifiedAt == live.modifiedAtEpochMs
                    }) current += ref
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Revoked, missing or changed sources cannot supply current evidence. */ }
        }
        return current
    }
}
