package com.pocketsteward.app.executor

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import java.security.MessageDigest

/**
 * Cryptographic content proofs used by the mutation journal.
 *
 * This is intentionally separate from duplicate detection. The journal uses
 * it to prove that a copy/write/trash outcome is the exact content that was
 * approved before recovery is allowed to call an interrupted mutation
 * committed.
 */
object StorageDigest {
    private const val BUFFER_BYTES = 64 * 1024

    suspend fun sha256(gateway: StorageGateway, ref: FileRef): String {
        val digest = MessageDigest.getInstance("SHA-256")
        gateway.openRead(ref).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
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
