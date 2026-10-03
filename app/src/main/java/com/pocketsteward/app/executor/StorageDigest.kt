package com.pocketsteward.app.executor

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive

/**
 * Mutation-journal witnesses, separate from duplicate detection.
 * Files use whole-content SHA-256. Intact native folder moves use a bounded
 * structural metadata witness, which does not certify equality of member bytes.
 */
object StorageDigest {
    private const val BUFFER_BYTES = 64 * 1024
    private const val FOLDER_PREFIX = "ps-folder-v1:"

    /** A native folder move retains a structural witness, never a claim of whole-byte equality. */
    suspend fun proof(gateway: StorageGateway, ref: FileRef): String {
        if (!gateway.stat(ref).isDirectory) return sha256(gateway, ref)
        val snapshot = com.pocketsteward.app.plan.SourcePreconditions.capture(gateway, ref)
        return "$FOLDER_PREFIX${requireNotNull(snapshot.directoryDigest)}:${snapshot.directoryEntryCount}:${snapshot.sizeBytes}"
    }

    fun isFolderProof(proof: String): Boolean = proof.startsWith(FOLDER_PREFIX)

    suspend fun matchesProof(gateway: StorageGateway, ref: FileRef, expected: String): Boolean {
        if (!isFolderProof(expected)) return sha256(gateway, ref).equals(expected, ignoreCase = true)
        val parts = expected.removePrefix(FOLDER_PREFIX).split(':')
        require(parts.size == 3 && parts[0].matches(Regex("[a-f0-9]{64}")) &&
            parts[1].toInt() in 0..100_000 && parts[2].toLong() >= 0) { "Malformed folder witness." }
        return gateway.stat(ref).isDirectory && proof(gateway, ref) == expected
    }

    suspend fun sha256(gateway: StorageGateway, ref: FileRef): String {
        val digest = MessageDigest.getInstance("SHA-256")
        gateway.openRead(ref).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
