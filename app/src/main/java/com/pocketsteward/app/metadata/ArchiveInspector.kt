package com.pocketsteward.app.metadata

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ArchiveInspection(val observedEntries: Int, val names: List<String>, val complete: Boolean, val note: String? = null)

/** Lists bounded metadata only. Entry paths are evidence, never extraction destinations. */
object ArchiveInspector {
    /**
     * Bumped whenever inspection results for an extension can change, so derived
     * caches stop reusing results from an older inspector. 2 added RAR and 7z.
     */
    const val REVISION = 2
    val supportedExtensions = setOf("zip", "apks", "xapk", "tar", "tgz", "gz", "rar", "7z")
    /** Header-only formats that need positional reads (7z keeps its header at the end). */
    val randomAccessExtensions = setOf("rar", "7z")
    private val LIMITS = ArchiveLimits()
    private const val MAX_ENTRIES = 2_000
    private const val MAX_SAMPLE = 40
    private const val MAX_DECODED = 8L * 1024 * 1024
    private const val PARTIAL_ENTRIES = "Entry limit reached; this is a partial archive sample."
    private const val PARTIAL_BYTES = "Inspection byte limit reached; this is a partial archive sample."
    private const val UNREADABLE = "Archive is unreadable, damaged, encrypted, or has an unsupported container. No files were extracted."

    fun zipFile(file: File, checkCancelled: () -> Unit = ::interruptCheck): ArchiveInspection = ZipFile(file).use { zip ->
        val names = mutableListOf<String>()
        val iterator = zip.entries()
        var count = 0
        while (iterator.hasMoreElements() && count < MAX_ENTRIES) {
            checkCancelled()
            val entry = iterator.nextElement()
            count++
            if (names.size < MAX_SAMPLE) names += entry.name.take(500)
        }
        val complete = !iterator.hasMoreElements()
        ArchiveInspection(count, names, complete, if (complete) null else PARTIAL_ENTRIES)
    }

    /** RAR/7z on a direct path: positional header reads only. */
    fun file(file: File, extension: String, checkCancelled: () -> Unit = ::interruptCheck): ArchiveInspection =
        FileInputStream(file).use { channel(it.channel, extension, checkCancelled) }

    /** RAR/7z on any seekable read-only channel, such as a SAF file descriptor. */
    fun channel(channel: SeekableByteChannel, extension: String, checkCancelled: () -> Unit = ::interruptCheck): ArchiveInspection =
        channel(channel, extension, LIMITS, checkCancelled)

    internal fun channel(channel: SeekableByteChannel, extension: String, limits: ArchiveLimits, checkCancelled: () -> Unit, prefixOnly: Boolean = false, observe: (BoundedArchiveReader) -> Unit = {}): ArchiveInspection {
        var reader: BoundedArchiveReader? = null
        return try {
            reader = BoundedArchiveReader(channel, channel.size(), limits.maxHeaderReadBytes, prefixOnly, checkCancelled)
            when (extension.lowercase()) {
                "rar" -> RarHeaderParser.inspect(reader, limits)
                "7z" -> SevenZipHeaderParser.inspect(reader, limits, checkCancelled)
                else -> ArchiveInspection(0, emptyList(), false, "This archive format is not supported.")
            }
        } catch (failure: Exception) {
            if (failure.isCancellation()) throw failure.asCancellation()
            ArchiveInspection(0, emptyList(), false, if (failure is ArchiveBudgetExceeded) PARTIAL_BYTES else UNREADABLE)
        } finally { reader?.let(observe) }
    }

    /**
     * RAR/7z from a non-seekable source: the compressed bytes are staged into one
     * owned temporary file under the app-private [stagingDirectory] and removed
     * afterwards. Other formats stream as before and never stage.
     */
    fun stream(input: InputStream, extension: String, stagingDirectory: File, checkCancelled: () -> Unit = ::interruptCheck, declaredSize: Long? = null): ArchiveInspection =
        stream(input, extension, stagingDirectory, LIMITS, checkCancelled, declaredSize)

    internal fun stream(input: InputStream, extension: String, stagingDirectory: File, limits: ArchiveLimits, checkCancelled: () -> Unit, declaredSize: Long? = null, observe: (BoundedArchiveReader) -> Unit = {}): ArchiveInspection {
        val ext = extension.lowercase()
        if (ext !in randomAccessExtensions) return stream(input, extension, checkCancelled)
        // A 7z header sits at the end; a prefix can never contain it.
        if (ext == "7z" && declaredSize != null && declaredSize > limits.maxStagedBytes) {
            return ArchiveInspection(0, emptyList(), false, "$PARTIAL_BYTES This source cannot seek and the 7z header lies beyond the ${limits.maxStagedBytes / (1024 * 1024)} MiB staging budget. No files were extracted.")
        }
        return try {
            ArchiveStaging.withStagedPrefix(input, stagingDirectory, limits.maxStagedBytes, checkCancelled) { staged, prefixOnly ->
                FileInputStream(staged).use { channel(it.channel, ext, limits, checkCancelled, prefixOnly, observe) }
            }
        } catch (failure: Exception) {
            if (failure.isCancellation()) throw failure.asCancellation()
            ArchiveInspection(0, emptyList(), false, "Archive could not be staged for inspection. No files were extracted.")
        }
    }

    fun stream(input: InputStream, extension: String): ArchiveInspection = stream(input, extension, ::interruptCheck)

    fun stream(input: InputStream, extension: String, checkCancelled: () -> Unit): ArchiveInspection {
        val names = mutableListOf<String>()
        var count = 0
        return try {
            when (extension.lowercase()) {
                "zip", "apks", "xapk" -> ZipInputStream(BudgetInput(input, 64L * 1024 * 1024, checkCancelled)).use { zip ->
                    val decoded = BudgetInput(zip, MAX_DECODED, checkCancelled)
                    val buffer = ByteArray(8192)
                    while (count < MAX_ENTRIES) {
                        checkCancelled()
                        val entry = zip.nextEntry ?: return ArchiveInspection(count, names, true)
                        count++
                        if (names.size < MAX_SAMPLE) names += entry.name.take(500)
                        while (decoded.read(buffer) != -1) { checkCancelled() /* consume through the decoded byte budget */ }
                    }
                }
                "tar", "tgz", "gz" -> {
                    val compressed = BudgetInput(input, 64L * 1024 * 1024, checkCancelled)
                    val decoded = if (extension.lowercase() == "tar") compressed else GZIPInputStream(compressed)
                    TarArchiveInputStream(BudgetInput(decoded, MAX_DECODED, checkCancelled)).use { tar ->
                        while (count < MAX_ENTRIES) {
                            checkCancelled()
                            val entry = tar.nextEntry ?: return ArchiveInspection(count, names, true)
                            count++
                            if (names.size < MAX_SAMPLE) names += entry.name.take(500)
                        }
                    }
                }
                "rar", "7z" -> return ArchiveInspection(0, emptyList(), false,
                    "RAR and 7z listing needs a seekable source or an app-private staging copy; nothing was read.")
                else -> return ArchiveInspection(0, emptyList(), false, "This archive format is not supported.")
            }
            ArchiveInspection(count, names, false, PARTIAL_ENTRIES)
        } catch (_: BudgetExceeded) {
            ArchiveInspection(count, names, false, PARTIAL_BYTES)
        } catch (failure: Exception) {
            if (failure.isCancellation()) throw failure.asCancellation()
            ArchiveInspection(count, names, false, UNREADABLE)
        }
    }
    private class BudgetExceeded : java.io.IOException()
    private class BudgetInput(input: InputStream, private val limit: Long, private val checkCancelled: () -> Unit) : FilterInputStream(input) {
        private var consumed = 0L
        override fun read(): Int {
            checkCancelled()
            if (consumed >= limit) throw BudgetExceeded()
            return super.read().also { if (it >= 0) consumed++ }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            if (length == 0) return 0
            if (consumed >= limit) throw BudgetExceeded()
            return `in`.read(buffer, offset, minOf(length.toLong(), limit - consumed).toInt()).also {
                if (it == 0) throw java.io.IOException("Archive stream made no read progress.")
                if (it > 0) consumed += it
            }
        }
        override fun skip(n: Long): Long {
            var skipped = 0L
            val buffer = ByteArray(8192)
            while (skipped < n) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }
    }
}
