package com.pocketsteward.app.evidence

import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Bounded cache freshness sample. This is never an executor integrity hash or mutation authority. */
object EvidenceFingerprint {
    const val WINDOW_BYTES = 4096
    const val MAX_READ_BYTES = WINDOW_BYTES * 2
    const val PREFIX = "evidence-sample-v1:"

    fun read(input: InputStream, declaredSize: Long, checkCancelled: () -> Unit = {}): String {
        require(declaredSize >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putLong(declaredSize).array())
        fun window(): ByteArray {
            val buffer = ByteArray(WINDOW_BYTES)
            var count = 0
            while (count < buffer.size) {
                checkCancelled()
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    buffer[count++] = byte.toByte()
                } else count += read
            }
            checkCancelled()
            return if (count == buffer.size) buffer else buffer.copyOf(count)
        }
        val head = window()
        digest.update(ByteBuffer.allocate(4).putInt(head.size).array())
        digest.update(head)
        // Seek only: draining/skip on a provider stream could read an entire large source.
        val channel = (input as? FileInputStream)?.channel
        val tailPosition = if (channel != null && declaredSize > WINDOW_BYTES) {
            runCatching {
                require(channel.size() == declaredSize) { "Source length changed during its cache sample." }
                (declaredSize - WINDOW_BYTES).also { channel.position(it) }
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // A file stream with a changed measurable size fails closed. Nonseekable descriptors use a prefix.
                val size = runCatching { channel.size() }.getOrNull()
                if (size != null && size > 0 && size != declaredSize) throw failure
                null
            }
        } else null
        digest.update(if (tailPosition == null) 0.toByte() else 1.toByte())
        if (tailPosition != null) {
            digest.update(ByteBuffer.allocate(8).putLong(tailPosition).array())
            val tail = window()
            digest.update(ByteBuffer.allocate(4).putInt(tail.size).array())
            digest.update(tail)
        }
        return PREFIX + (if (tailPosition == null) "prefix:" else "prefix-tail:") +
            digest.digest().let { bytes ->
                val alphabet = "0123456789abcdef"
                val hex = CharArray(bytes.size * 2)
                bytes.forEachIndexed { index, byte ->
                    val value = byte.toInt() and 255
                    hex[index * 2] = alphabet[value shr 4]
                    hex[index * 2 + 1] = alphabet[value and 15]
                }
                String(hex)
            }
    }
}
