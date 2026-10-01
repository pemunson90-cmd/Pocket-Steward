package com.pocketsteward.app.metadata

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ArchiveInspection(val observedEntries: Int, val names: List<String>, val complete: Boolean, val note: String? = null)

/** Lists bounded metadata only. Entry paths are evidence, never extraction destinations. */
object ArchiveInspector {
    val supportedExtensions = setOf("zip", "apks", "xapk", "tar", "tgz", "gz")
    private const val MAX_ENTRIES = 2_000
    private const val MAX_SAMPLE = 40
    private const val MAX_DECODED = 8L * 1024 * 1024

    fun zipFile(file: File): ArchiveInspection = ZipFile(file).use { zip ->
        val names = mutableListOf<String>()
        val iterator = zip.entries()
        var count = 0
        while (iterator.hasMoreElements() && count < MAX_ENTRIES) {
            val entry = iterator.nextElement()
            count++
            if (names.size < MAX_SAMPLE) names += entry.name.take(500)
        }
        val complete = !iterator.hasMoreElements()
        ArchiveInspection(count, names, complete, if (complete) null else "Entry limit reached; this is a partial archive sample.")
    }

    fun stream(input: InputStream, extension: String): ArchiveInspection {
        val names = mutableListOf<String>()
        var count = 0
        return try {
            when (extension.lowercase()) {
                "zip", "apks", "xapk" -> ZipInputStream(BudgetInput(input, 64L * 1024 * 1024)).use { zip ->
                    val decoded = BudgetInput(zip, MAX_DECODED)
                    val buffer = ByteArray(8192)
                    while (count < MAX_ENTRIES) {
                        val entry = zip.nextEntry ?: return ArchiveInspection(count, names, true)
                        count++
                        if (names.size < MAX_SAMPLE) names += entry.name.take(500)
                        while (decoded.read(buffer) != -1) { /* consume through the decoded byte budget */ }
                    }
                }
                "tar", "tgz", "gz" -> {
                    val compressed = BudgetInput(input, 64L * 1024 * 1024)
                    val decoded = if (extension.lowercase() == "tar") compressed else GZIPInputStream(compressed)
                    TarArchiveInputStream(BudgetInput(decoded, MAX_DECODED)).use { tar ->
                        while (count < MAX_ENTRIES) {
                            val entry = tar.nextEntry ?: return ArchiveInspection(count, names, true)
                            count++
                            if (names.size < MAX_SAMPLE) names += entry.name.take(500)
                        }
                    }
                }
                else -> return ArchiveInspection(0, emptyList(), false, "This archive format is not supported.")
            }
            ArchiveInspection(count, names, false, "Entry limit reached; this is a partial archive sample.")
        } catch (_: BudgetExceeded) {
            ArchiveInspection(count, names, false, "Inspection byte limit reached; this is a partial archive sample.")
        } catch (_: Exception) {
            ArchiveInspection(count, names, false, "Archive is unreadable, damaged, encrypted, or has an unsupported container. No files were extracted.")
        }
    }
    private class BudgetExceeded : java.io.IOException()
    private class BudgetInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var consumed = 0L
        override fun read(): Int {
            if (consumed >= limit) throw BudgetExceeded()
            return super.read().also { if (it >= 0) consumed++ }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (consumed >= limit) throw BudgetExceeded()
            return `in`.read(buffer, offset, minOf(length.toLong(), limit - consumed).toInt()).also { if (it > 0) consumed += it }
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
