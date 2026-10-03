package com.pocketsteward.app.metadata

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.CancellationException

/**
 * Limits for header-only RAR/7z inspection. Every value bounds real reads or
 * allocations, not just declared sizes.
 */
internal data class ArchiveLimits(
    val maxEntries: Int = 2_000,
    val maxSample: Int = 40,
    val maxNameChars: Int = 500,
    /** Total bytes a random-access parser may read from the archive (headers only). */
    val maxHeaderReadBytes: Long = 8L * 1024 * 1024,
    /** Decoded 7z header bytes (LZMA/LZMA2 output) and dictionary memory. */
    val maxDecodedBytes: Long = 8L * 1024 * 1024,
    /** Compressed bytes copied from a non-seekable source into an owned staging file. */
    val maxStagedBytes: Long = 64L * 1024 * 1024,
    /** Header blocks visited (files plus service/recovery/comment blocks). */
    val maxBlocks: Int = 20_000,
)

internal class ArchiveTruncated(message: String) : IOException(message)
internal class ArchiveDamaged(message: String) : IOException(message)
internal class ArchiveBudgetExceeded(message: String) : IOException(message)

internal fun Throwable.isCancellation(): Boolean =
    this is CancellationException || this is InterruptedException ||
        this is InterruptedIOException || this is ClosedByInterruptException

/**
 * Surfaces any interruption as a [CancellationException] (keeping the cause) so
 * coroutine callers that rethrow cancellation keep doing so. A caught
 * [InterruptedException] has cleared the thread flag, so it is restored.
 */
internal fun Throwable.asCancellation(): CancellationException {
    if (this is InterruptedException) Thread.currentThread().interrupt()
    return this as? CancellationException ?: CancellationException("Archive inspection was interrupted.").also { it.initCause(this) }
}

/** Checks the calling thread's interrupt flag; used when no coroutine check is supplied. */
internal fun interruptCheck() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Archive inspection was interrupted.")
}

/**
 * Positional, read-only access to an archive. Positions are seeks, never skips,
 * so jumping over packed member data costs no reads. Every byte returned is
 * charged to [readBudget]; each allocation is at most the requested length,
 * which callers validate before asking.
 *
 * [visibleSize] may be smaller than the underlying source when only a staged
 * prefix exists; reads past it then report the staging budget, not damage.
 */
internal class BoundedArchiveReader(
    private val channel: SeekableByteChannel,
    val visibleSize: Long,
    private val readBudget: Long,
    val prefixOnly: Boolean = false,
    private val checkCancelled: () -> Unit = ::interruptCheck,
) {
    var bytesRead = 0L
        private set
    var readCalls = 0
        private set
    var largestRead = 0
        private set

    fun remainingBudget(): Long = readBudget - bytesRead

    fun read(position: Long, length: Int): ByteArray {
        checkCancelled()
        require(length >= 0)
        if (position < 0 || position > visibleSize || length > visibleSize - position) {
            if (prefixOnly) throw ArchiveBudgetExceeded("Header lies beyond the staged prefix.")
            throw ArchiveTruncated("Header extends beyond the end of the archive.")
        }
        if (length > remainingBudget()) throw ArchiveBudgetExceeded("Header read budget exhausted.")
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        channel.position(position)
        while (buffer.hasRemaining()) {
            checkCancelled()
            val read = channel.read(buffer)
            if (read < 0) throw ArchiveTruncated("Archive ended while reading a header.")
            readCalls++
        }
        bytesRead += length
        largestRead = maxOf(largestRead, length)
        return bytes
    }

    /** Reads up to [length] bytes, fewer only when the archive ends first. */
    fun readAvailable(position: Long, length: Int): ByteArray =
        read(position, minOf(length.toLong(), maxOf(0L, visibleSize - position)).toInt())
}

/** Collects bounded, untrusted member names. Names are evidence only, never paths to write. */
internal class EntryCollector(private val limits: ArchiveLimits) {
    var count = 0
        private set
    val names = mutableListOf<String>()

    /** Returns false when the entry limit has already been reached and [name] was not counted. */
    fun add(name: String?): Boolean {
        if (count >= limits.maxEntries) return false
        count++
        if (name != null && names.size < limits.maxSample) names += sampleName(name, limits.maxNameChars)
        return true
    }

    fun inspection(complete: Boolean, note: String?) = ArchiveInspection(count, names.toList(), complete, note)
}

internal fun sampleName(name: String, maxChars: Int): String {
    val clipped = name.take(maxChars)
    return if (clipped.isNotEmpty() && clipped.length < name.length && Character.isHighSurrogate(clipped.last())) clipped.dropLast(1) else clipped
}

internal fun crc32(bytes: ByteArray, offset: Int, length: Int): Long =
    java.util.zip.CRC32().apply { update(bytes, offset, length) }.value

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
internal fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)
