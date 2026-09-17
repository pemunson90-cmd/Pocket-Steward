package com.pocketsteward.app.dedupe

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.parseFileRef
import java.security.MessageDigest

data class DuplicateGroup(val sha256: String, val members: List<FileRecord>)

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
 * reports groups. What happens to a group is a planning decision made
 * elsewhere, same separation as everywhere else in this app.
 */
class DuplicateDetector(private val gateway: StorageGateway) {

    suspend fun findDuplicates(records: List<FileRecord>): List<DuplicateGroup> {
        val bySize = records
            .filter { !it.isDirectory }
            .groupBy { it.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatten()

        val byQuickFingerprint = groupBy(bySize) { quickFingerprint(it) }
            .filter { it.size > 1 }
            .flatten()

        return groupBy(byQuickFingerprint) { sha256(it) }
            .filter { it.size > 1 }
            .map { group -> DuplicateGroup(sha256 = sha256(group.first()), members = group) }
    }

    private suspend fun quickFingerprint(record: FileRecord): String {
        val digest = MessageDigest.getInstance("SHA-256")
        gateway.openRead(parseFileRef(record.stableRef)).use { stream ->
            val buffer = ByteArray(QUICK_FINGERPRINT_BYTES)
            val read = stream.read(buffer)
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private suspend fun sha256(record: FileRecord): String {
        val digest = MessageDigest.getInstance("SHA-256")
        gateway.openRead(parseFileRef(record.stableRef)).use { stream ->
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private suspend fun groupBy(records: List<FileRecord>, key: suspend (FileRecord) -> String): List<List<FileRecord>> {
        val grouped = LinkedHashMap<String, MutableList<FileRecord>>()
        for (record in records) {
            grouped.getOrPut(key(record)) { mutableListOf() }.add(record)
        }
        return grouped.values.toList()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val QUICK_FINGERPRINT_BYTES = 4096
        const val READ_CHUNK_BYTES = 64 * 1024
    }
}
