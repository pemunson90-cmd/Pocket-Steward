package com.pocketsteward.app.dedupe

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.parseFileRef
import java.security.MessageDigest

/** Where the cascade currently is, for a UI that would otherwise show nothing for minutes. */
data class DedupeProgress(val phase: String, val processed: Int, val total: Int)

data class DuplicateGroup(val sha256: String, val members: List<FileRecord>) {
    /** The copy that survives. [members] arrives ordered keeper-first, per [KeeperSelector]. */
    val keeper: FileRecord get() = members.first()

    /** Every other copy — what a trash-the-duplicates plan would act on. */
    val extras: List<FileRecord> get() = members.drop(1)
}

/**
 * Plan Section 8's cascade, exactly: group by exact size first (free, from
 * the index alone), drop singleton groups, then only for what's left do any
 * real I/O — a cheap quick fingerprint first, then a full SHA-256 only for
 * files that still collide on the quick fingerprint. This is what keeps
 * "find duplicates" from hashing every byte of every file in the scope,
 * which Section 8 explicitly forbids as a default behavior.
 *
 * "Exact duplicate" means a matching cryptographic hash, full stop — never
 * inferred from filename, size, or a model's own judgment (Section 8's own
 * closing line). This class never deletes or trashes anything; it only
 * reports groups, already ordered so the keeper is first. What happens to a
 * group is a planning decision made elsewhere, same separation as everywhere
 * else in this app.
 *
 * [findDuplicates] reports progress per file hashed. On a real scope (Pat's
 * Downloads: 13,738 files, 11.2 GB) the I/O phases take long enough that a
 * caller with no progress to show is indistinguishable from a dead button.
 */
class DuplicateDetector(
    private val gateway: StorageGateway,
    private val fileRecordDao: FileRecordDao? = null,
) {

    suspend fun findDuplicates(
        records: List<FileRecord>,
        onProgress: (DedupeProgress) -> Unit = {},
    ): List<DuplicateGroup> {
        val sizeCandidates = records
            .filter { !it.isDirectory }
            .groupBy { it.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatten()

        var fingerprinted = 0
        val byQuickFingerprint = groupBy(sizeCandidates) { record ->
            quickFingerprint(record).also {
                onProgress(DedupeProgress(PHASE_FINGERPRINT, ++fingerprinted, sizeCandidates.size))
            }
        }

        val hashCandidates = byQuickFingerprint.values.filter { it.size > 1 }.flatten()

        var hashed = 0
        val byHash = groupBy(hashCandidates) { record ->
            sha256(record).also {
                onProgress(DedupeProgress(PHASE_CONFIRM, ++hashed, hashCandidates.size))
            }
        }

        return byHash
            .filter { (_, members) -> members.size > 1 }
            .map { (hash, members) -> DuplicateGroup(sha256 = hash, members = keeperFirst(members)) }
    }

    /**
     * Reorders a confirmed-identical group so [DuplicateGroup.keeper] is the
     * copy [KeeperSelector] chose, rather than whichever one the grouping
     * happened to encounter first. Everything downstream — the review screen's
     * label, the trash plan's `extras` — reads that ordering, so the decision
     * lives in exactly one place.
     */
    private fun keeperFirst(members: List<FileRecord>): List<FileRecord> {
        val keeperIndex = KeeperSelector.keeperIndex(
            members.map { KeeperCandidate(it.stableRef, it.modifiedAt, it.displayName) },
        )
        return listOf(members[keeperIndex]) + members.filterIndexed { index, _ -> index != keeperIndex }
    }

    private suspend fun quickFingerprint(record: FileRecord): String {
        record.quickFingerprint?.takeIf { it.isNotBlank() }?.let { return it }

        val digest = MessageDigest.getInstance("SHA-256")
        val ref = parseFileRef(record.stableRef)
        gateway.openRead(ref).use { stream ->
            val buffer = ByteArray(QUICK_FINGERPRINT_BYTES)
            val read = stream.read(buffer)
            if (read > 0) digest.update(buffer, 0, read)
        }
        val value = digest.digest().toHex()
        if (stillSameIndexedFile(record, ref)) {
            fileRecordDao?.updateQuickFingerprint(record.stableRef, value)
        }
        return value
    }

    private suspend fun sha256(record: FileRecord): String {
        record.sha256?.takeIf { it.isNotBlank() }?.let { return it }

        val digest = MessageDigest.getInstance("SHA-256")
        val ref = parseFileRef(record.stableRef)
        gateway.openRead(ref).use { stream ->
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val value = digest.digest().toHex()
        if (stillSameIndexedFile(record, ref)) {
            fileRecordDao?.updateSha256(record.stableRef, value)
        }
        return value
    }

    private suspend fun stillSameIndexedFile(record: FileRecord, ref: com.pocketsteward.app.storage.FileRef): Boolean {
        val current = runCatching { gateway.stat(ref) }.getOrNull() ?: return false
        if (current.isDirectory || current.sizeBytes != record.sizeBytes) return false

        val indexedModified = record.modifiedAt
        val currentModified = current.modifiedAtEpochMs
        return indexedModified == null || currentModified == null || indexedModified == currentModified
    }

    /**
     * Keyed grouping that keeps the key — the previous version threw it away
     * and then re-hashed one file per group to recover it, a full second read
     * of a potentially large file for a value already computed.
     */
    private suspend fun groupBy(
        records: List<FileRecord>,
        key: suspend (FileRecord) -> String,
    ): Map<String, List<FileRecord>> {
        val grouped = LinkedHashMap<String, MutableList<FileRecord>>()
        for (record in records) {
            grouped.getOrPut(key(record)) { mutableListOf() }.add(record)
        }
        return grouped
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val QUICK_FINGERPRINT_BYTES = 4096
        const val READ_CHUNK_BYTES = 64 * 1024
        const val PHASE_FINGERPRINT = "Fingerprinting"
        const val PHASE_CONFIRM = "Confirming matches"
    }
}
