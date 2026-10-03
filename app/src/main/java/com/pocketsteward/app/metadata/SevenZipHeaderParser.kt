package com.pocketsteward.app.metadata

import org.tukaani.xz.LZMA2InputStream
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Header-only 7z listing. Reads the 32-byte signature header and the next header
 * at the end of the archive; packed member streams are never read or decoded.
 *
 * Plain headers and headers compressed with LZMA, LZMA2 or Copy (7-Zip's default
 * header compression) are supported. Decoding is capped at
 * [ArchiveLimits.maxDecodedBytes] of output and the same amount of dictionary.
 * AES-encrypted headers, other header filters and external name streams are
 * reported explicitly.
 */
internal object SevenZipHeaderParser {
    private val SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val AES = byteArrayOf(0x06, 0xF1.toByte(), 0x07, 0x01)
    private val LZMA = byteArrayOf(0x03, 0x01, 0x01)
    private val LZMA2 = byteArrayOf(0x21)
    private val COPY = byteArrayOf(0x00)
    private const val MAX_CODERS = 64
    private const val PARTIAL_BUDGET = "Inspection byte limit reached; this is a partial archive sample."

    private const val K_END = 0x00L
    private const val K_HEADER = 0x01L
    private const val K_ARCHIVE_PROPERTIES = 0x02L
    private const val K_ADDITIONAL_STREAMS = 0x03L
    private const val K_MAIN_STREAMS = 0x04L
    private const val K_FILES = 0x05L
    private const val K_PACK_INFO = 0x06L
    private const val K_UNPACK_INFO = 0x07L
    private const val K_SUBSTREAMS = 0x08L
    private const val K_SIZE = 0x09L
    private const val K_CRC = 0x0AL
    private const val K_FOLDER = 0x0BL
    private const val K_CODERS_UNPACK_SIZE = 0x0CL
    private const val K_NUM_UNPACK_STREAM = 0x0DL
    private const val K_NAME = 0x11L
    private const val K_ENCODED_HEADER = 0x17L

    fun inspect(reader: BoundedArchiveReader, limits: ArchiveLimits, checkCancelled: () -> Unit = ::interruptCheck): ArchiveInspection {
        val parse = Parse(limits)
        val stop = try {
            parse.run(reader, checkCancelled)
        } catch (failure: Exception) {
            if (failure.isCancellation()) throw failure.asCancellation()
            when {
                failure is ArchiveBudgetExceeded || (failure is ArchiveTruncated && reader.prefixOnly) -> PARTIAL_BUDGET
                failure is ArchiveTruncated && parse.decodedPrefixOnly ->
                    "The 7z header is larger than the ${budgetText(limits.maxDecodedBytes)} decoded budget; only names from its first part were read."
                failure is ArchiveTruncated -> "7z archive is truncated or is the first part of a split archive; the header is not available."
                failure is ArchiveDamaged -> "7z archive is damaged (${failure.message}); names could not be fully listed."
                failure is Unsupported -> failure.message
                else -> "7z archive is unreadable or damaged; names could not be fully listed."
            }
        }
        val notes = mutableListOf<String>()
        stop?.let(notes::add)
        parse.declaredFiles?.takeIf { stop != null || it > limits.maxEntries }?.let { notes += "The header declares $it entries." }
        if (parse.encryptedContent) notes += "Member contents are encrypted; only stored names were read."
        if (parse.namesMissing) notes += "The archive stores no member names."
        val complete = stop == null && parse.listedAll
        return parse.entries.inspection(complete, if (notes.isEmpty()) null else (notes + "No files were extracted.").joinToString(" "))
    }

    private fun budgetText(bytes: Long) = if (bytes % (1024 * 1024) == 0L) "${bytes / (1024 * 1024)} MiB" else "$bytes-byte"

    private class Unsupported(message: String) : java.io.IOException(message)

    private class Folder(val coders: List<Pair<ByteArray, ByteArray>>, val outStreams: Int)

    private class Parse(val limits: ArchiveLimits) {
        val entries = EntryCollector(limits)
        var declaredFiles: Long? = null
        var listedAll = false
        var encryptedContent = false
        var namesMissing = false
        var decodedPrefixOnly = false

        fun run(reader: BoundedArchiveReader, checkCancelled: () -> Unit): String? {
            val start = reader.readAvailable(0, 32)
            if (start.size < SIGNATURE.size || !SIGNATURE.indices.all { start[it] == SIGNATURE[it] }) {
                throw Unsupported("No 7z signature at the start of the file (self-extracting, misnamed or damaged).")
            }
            if (start.size < 32) throw ArchiveTruncated("short signature header")
            if (start.u8(6) != 0) throw Unsupported("7z format version ${start.u8(6)}.${start.u8(7)} is not supported; only version 0.x headers can be listed.")
            if (crc32(start, 12, 20) != start.u32(8)) throw ArchiveDamaged("start header checksum mismatch")
            val nextOffset = start.u64(12)
            val nextSize = start.u64(20)
            if (nextOffset < 0 || nextSize < 0) throw ArchiveDamaged("invalid header location")
            if (nextSize == 0L) { listedAll = true; return null } // Empty archive.
            val headerPosition = try { Math.addExact(32L, nextOffset) } catch (_: ArithmeticException) { throw ArchiveDamaged("invalid header location") }
            if (headerPosition > reader.visibleSize || nextSize > reader.visibleSize - headerPosition) throw ArchiveTruncated("header beyond end")
            if (nextSize > reader.remainingBudget()) throw ArchiveBudgetExceeded("header larger than read budget")
            val raw = reader.read(headerPosition, nextSize.toInt())
            if (crc32(raw, 0, raw.size) != start.u32(28)) throw ArchiveDamaged("header checksum mismatch")
            var header = Cursor(raw, 0, raw.size)
            when (header.number()) {
                K_HEADER -> Unit
                K_ENCODED_HEADER -> {
                    header = Cursor(decodeHeader(header, reader, checkCancelled), 0)
                    if (header.number() != K_HEADER) throw ArchiveDamaged("decoded header has no header marker")
                }
                else -> throw ArchiveDamaged("unknown header type")
            }
            return try { parseHeader(header, checkCancelled) } catch (ended: ArchiveTruncated) {
                // The whole header was read and checksummed, so running off its end is damage,
                // unless only a budgeted prefix of a larger decoded header exists.
                if (decodedPrefixOnly) throw ended else throw ArchiveDamaged("header ended early")
            }
        }

        private fun decodeHeader(cursor: Cursor, reader: BoundedArchiveReader, checkCancelled: () -> Unit): ByteArray {
            var packPosition = -1L
            var packSize = -1L
            var folder: Folder? = null
            var unpackSize = -1L
            var crc: Long? = null
            streamsInfo(cursor, onPack = { position, sizes -> packPosition = position; packSize = sizes.singleOrNull() ?: throw Unsupported("This 7z header uses several packed streams, which is not supported.") },
                onFolders = { folders, sizes, firstCrc, _ ->
                    if (folders.size > 1) throw Unsupported("This 7z header uses several folders, which is not supported.")
                    folder = folders.singleOrNull() ?: throw ArchiveDamaged("encoded header has no folder")
                    unpackSize = sizes.single().lastOrNull() ?: throw ArchiveDamaged("encoded header has no size"); crc = firstCrc
                })
            val headerFolder = folder ?: throw ArchiveDamaged("encoded header has no folder")
            val coders = headerFolder.coders
            if (coders.any { it.first.contentEquals(AES) }) throw Unsupported("7z headers are encrypted; member names cannot be read without a password.")
            if (coders.size != 1 || headerFolder.outStreams != 1) throw Unsupported("This 7z header uses a filter chain that is not supported for listing.")
            if (packPosition < 0 || packSize < 0 || unpackSize < 0) throw ArchiveDamaged("encoded header sizes are invalid")
            val position = try { Math.addExact(32L, packPosition) } catch (_: ArithmeticException) { throw ArchiveDamaged("invalid packed header location") }
            if (position > reader.visibleSize || packSize > reader.visibleSize - position) throw ArchiveTruncated("packed header beyond end")
            if (packSize > reader.remainingBudget()) throw ArchiveBudgetExceeded("packed header larger than read budget")
            val packed = reader.read(position, packSize.toInt())
            val outLimit = minOf(unpackSize, limits.maxDecodedBytes).toInt()
            decodedPrefixOnly = unpackSize > limits.maxDecodedBytes
            val (id, props) = coders.single()
            val input: InputStream = when {
                id.contentEquals(COPY) -> ByteArrayInputStream(packed)
                id.contentEquals(LZMA) -> {
                    if (props.size < 5) throw ArchiveDamaged("LZMA properties are missing")
                    // Distances can never exceed the bytes produced, so a dictionary
                    // larger than outLimit is never needed for this bounded prefix.
                    val dictionary = minOf(props.u32(1), outLimit.toLong()).toInt()
                    if (LZMAInputStream.getMemoryUsage(dictionary, props[0]) > memoryCeilingKiB()) throw ArchiveBudgetExceeded("LZMA memory")
                    // A full decode passes the exact size so xz verifies the stream ends cleanly; a
                    // budgeted prefix uses an unknown size so stopping early is not reported as damage.
                    LZMAInputStream(ByteArrayInputStream(packed), if (decodedPrefixOnly) -1L else outLimit.toLong(), props[0], dictionary)
                }
                id.contentEquals(LZMA2) -> {
                    if (props.isEmpty() || props.u8(0) > 40) throw ArchiveDamaged("LZMA2 properties are invalid")
                    val code = props.u8(0)
                    val declared = if (code == 40) 0xFFFF_FFFFL else (2L or (code and 1).toLong()) shl (code / 2 + 11)
                    val dictionary = maxOf(4096L, minOf(declared, outLimit.toLong())).toInt()
                    if (LZMA2InputStream.getMemoryUsage(dictionary) > memoryCeilingKiB()) throw ArchiveBudgetExceeded("LZMA2 memory")
                    LZMA2InputStream(ByteArrayInputStream(packed), dictionary)
                }
                else -> throw Unsupported("This 7z header uses compression method ${id.joinToString("") { "%02X".format(it) }}, which is not supported for listing.")
            }
            // Grows with bytes actually decoded, so a false declared size cannot force the full budget up front.
            var decoded = ByteArray(minOf(outLimit, 65_536))
            input.use {
                var filled = 0
                while (filled < outLimit) {
                    checkCancelled()
                    if (filled == decoded.size) decoded = decoded.copyOf(minOf(outLimit.toLong(), decoded.size * 2L).toInt())
                    val read = try { it.read(decoded, filled, minOf(65_536, decoded.size - filled)) } catch (failure: java.io.IOException) {
                        if (failure.isCancellation()) throw failure.asCancellation()
                        throw ArchiveDamaged("header stream does not decode")
                    }
                    if (read < 0) throw ArchiveDamaged("decoded header is shorter than declared")
                    filled += read
                }
            }
            if (!decodedPrefixOnly && crc != null && crc32(decoded, 0, decoded.size) != crc) throw ArchiveDamaged("decoded header checksum mismatch")
            return decoded
        }

        /** Dictionary (at most the decoded budget) plus up to 8 MiB of LZMA probability tables (lc+lp <= 12). */
        private fun memoryCeilingKiB(): Long = limits.maxDecodedBytes / 1024 + 8 * 1024

        private fun parseHeader(cursor: Cursor, checkCancelled: () -> Unit): String? {
            var id = cursor.number()
            if (id == K_ARCHIVE_PROPERTIES) {
                while (true) { val type = cursor.number(); if (type == K_END) break; cursor.skip(cursor.number()) }
                id = cursor.number()
            }
            if (id == K_ADDITIONAL_STREAMS) { streamsInfo(cursor); id = cursor.number() }
            if (id == K_MAIN_STREAMS) {
                streamsInfo(cursor, onFolders = { _, _, _, encrypted -> if (encrypted) encryptedContent = true }, collectFolders = false)
                id = cursor.number()
            }
            if (id == K_END) { listedAll = true; return null } // No files.
            if (id != K_FILES) throw ArchiveDamaged("unexpected header property")
            return files(cursor, checkCancelled)
        }

        private fun files(cursor: Cursor, checkCancelled: () -> Unit): String? {
            val numFiles = cursor.number()
            if (numFiles < 0) throw ArchiveDamaged("invalid entry count")
            declaredFiles = numFiles
            var namesSeen = false
            while (true) {
                checkCancelled()
                val type = cursor.number()
                if (type == K_END) break
                val size = cursor.number()
                val end = cursor.limitFor(size)
                if (type == K_NAME) {
                    namesSeen = true
                    if (cursor.byte() != 0) throw Unsupported("7z member names are stored in an external stream, which is not supported for listing.")
                    var index = 0L
                    while (index < numFiles) {
                        if (index % 256 == 0L) checkCancelled()
                        val keep = entries.names.size < limits.maxSample
                        val name = cursor.utf16Name(end, if (keep) limits.maxNameChars + 1 else 0)
                        if (!entries.add(if (keep) name else null)) return "Entry limit reached; this is a partial archive sample."
                        index++
                    }
                    if (cursor.position != end) throw ArchiveDamaged("name list does not match entry count")
                }
                cursor.position = end
            }
            if (!namesSeen) {
                namesMissing = numFiles > 0
                var index = 0L
                while (index < numFiles) { if (!entries.add(null)) return "Entry limit reached; this is a partial archive sample."; index++ }
            }
            listedAll = true
            return null
        }

        /**
         * Walks PackInfo/UnpackInfo/SubStreamsInfo without per-folder tables. Folder
         * details are kept only when [collectFolders] is set (the encoded header,
         * which must contain one folder).
         */
        private fun streamsInfo(
            cursor: Cursor,
            onPack: (Long, List<Long>) -> Unit = { _, _ -> },
            onFolders: (folders: List<Folder>, sizes: List<List<Long>>, firstCrc: Long?, anyEncrypted: Boolean) -> Unit = { _, _, _, _ -> },
            collectFolders: Boolean = true,
        ) {
            var numFolders = 0L
            var folderCrcs: Digests? = null
            var id = cursor.number()
            if (id == K_PACK_INFO) {
                val packPosition = cursor.number()
                val numPackStreams = cursor.count()
                val sizes = mutableListOf<Long>()
                while (true) {
                    val type = cursor.number()
                    if (type == K_END) break
                    when (type) {
                        K_SIZE -> repeatLong(numPackStreams) { cursor.number().also { if (sizes.size < 2) sizes += it } }
                        K_CRC -> digests(cursor, numPackStreams)
                        else -> cursor.skip(cursor.number())
                    }
                }
                onPack(packPosition, sizes)
                id = cursor.number()
            }
            if (id == K_UNPACK_INFO) {
                if (cursor.number() != K_FOLDER) throw ArchiveDamaged("missing folder list")
                numFolders = cursor.count()
                if (cursor.byte() != 0) throw Unsupported("This 7z archive stores folder definitions externally, which is not supported for listing.")
                // Only the first two folders are retained (enough to reject multi-folder headers).
                val kept = mutableListOf<Folder>()
                var outStreams = 0L
                var anyEncrypted = false
                repeatLong(numFolders) {
                    val folder = folder(cursor)
                    outStreams += folder.outStreams
                    if (folder.coders.any { coder -> coder.first.contentEquals(AES) }) anyEncrypted = true
                    if (collectFolders && kept.size < 2) kept += folder
                }
                if (cursor.number() != K_CODERS_UNPACK_SIZE) throw ArchiveDamaged("missing unpack sizes")
                val sizes = kept.map { mutableListOf<Long>() }
                var keptIndex = 0
                repeatLong(outStreams) {
                    val size = cursor.number()
                    if (keptIndex < kept.size) {
                        sizes[keptIndex] += size
                        if (sizes[keptIndex].size == kept[keptIndex].outStreams) keptIndex++
                    }
                }
                while (true) {
                    val type = cursor.number()
                    if (type == K_END) break
                    if (type == K_CRC) folderCrcs = digests(cursor, numFolders) else cursor.skip(cursor.number())
                }
                onFolders(kept, sizes, folderCrcs?.value(0), anyEncrypted)
                id = cursor.number()
            }
            if (id == K_SUBSTREAMS) {
                var type = cursor.number()
                var streamsStart = -1
                if (type == K_NUM_UNPACK_STREAM) {
                    streamsStart = cursor.position
                    repeatLong(numFolders) { cursor.number() }
                    type = cursor.number()
                }
                fun streamsReader(): (Long) -> Long {
                    if (streamsStart < 0) return { 1L }
                    val replay = Cursor(cursor.bytes, streamsStart, cursor.end)
                    return { replay.number().also { if (it < 0) throw ArchiveDamaged("invalid stream count") } }
                }
                if (type == K_SIZE) {
                    val next = streamsReader()
                    repeatLong(numFolders) { index -> val streams = next(index); repeatLong(maxOf(0L, streams - 1)) { cursor.number() } }
                    type = cursor.number()
                }
                var digestCount = 0L
                val next = streamsReader()
                repeatLong(numFolders) { index ->
                    val streams = next(index)
                    if (streams != 1L || folderCrcs?.defined(index) != true) digestCount = addCount(digestCount, streams)
                }
                while (type != K_END) {
                    if (type == K_CRC) digests(cursor, digestCount) else cursor.skip(cursor.number())
                    type = cursor.number()
                }
                id = cursor.number()
            }
            if (id != K_END) throw ArchiveDamaged("unexpected streams property")
        }

        private fun folder(cursor: Cursor): Folder {
            val numCoders = cursor.count()
            if (numCoders !in 1..MAX_CODERS) throw ArchiveDamaged("invalid coder count")
            val coders = mutableListOf<Pair<ByteArray, ByteArray>>()
            var inStreams = 0L
            var outStreams = 0L
            repeat(numCoders.toInt()) {
                val flags = cursor.byte()
                if (flags and 0x80 != 0) throw Unsupported("This 7z archive uses alternative coder methods, which are not supported for listing.")
                val id = cursor.bytes(flags and 0x0F)
                var ins = 1L
                var outs = 1L
                if (flags and 0x10 != 0) {
                    ins = cursor.number(); outs = cursor.number()
                    if (ins !in 1..MAX_CODERS || outs !in 1..MAX_CODERS) throw ArchiveDamaged("invalid coder streams")
                }
                val props = if (flags and 0x20 != 0) cursor.bytes(cursor.count().toInt()) else ByteArray(0)
                coders += id to props
                inStreams += ins; outStreams += outs
            }
            val bindPairs = outStreams - 1
            repeatLong(bindPairs) { cursor.number(); cursor.number() }
            val packed = inStreams - bindPairs
            if (packed < 1) throw ArchiveDamaged("invalid bind pairs")
            if (packed > 1) repeatLong(packed) { cursor.number() }
            return Folder(coders, outStreams.toInt())
        }

        private class Digests(val all: Boolean, val bits: ByteArray?, val firstValue: Long?) {
            fun defined(index: Long): Boolean = all || (bits != null && index / 8 < bits.size && (bits[(index / 8).toInt()].toInt() shr (7 - (index % 8).toInt())) and 1 != 0)
            fun value(index: Int): Long? = if (index == 0 && defined(0)) firstValue else null
        }

        private fun digests(cursor: Cursor, count: Long): Digests {
            val all = cursor.byte() != 0
            val bits = if (all) null else cursor.bytes(cursor.checkedLength((count + 7) / 8))
            var defined = 0L
            if (all) defined = count else bits!!.forEach { defined += Integer.bitCount(it.toInt() and 0xFF) }
            val probe = Digests(all, bits, null)
            val firstValue = if (count > 0 && probe.defined(0)) cursor.peekU32() else null
            cursor.skip(cursor.checkedLength(defined) * 4L)
            return Digests(all, bits, firstValue)
        }

        private fun addCount(total: Long, more: Long): Long = try { Math.addExact(total, more) } catch (_: ArithmeticException) { throw ArchiveDamaged("stream count overflow") }

        private inline fun repeatLong(times: Long, action: (Long) -> Unit) {
            if (times < 0) throw ArchiveDamaged("negative count")
            var index = 0L
            while (index < times) { action(index); index++ }
        }
    }

    /** Bounded cursor over header bytes; every read checks the end before touching memory. */
    private class Cursor(val bytes: ByteArray, var position: Int, val end: Int = bytes.size) {
        fun remaining() = end - position

        fun byte(): Int {
            if (position >= end) throw ArchiveTruncated("header ended early")
            return bytes[position++].toInt() and 0xFF
        }

        /** 7z variable-length UINT64. */
        fun number(): Long {
            val first = byte()
            var mask = 0x80
            var value = 0L
            for (index in 0 until 8) {
                if (first and mask == 0) {
                    val high = (first and (mask - 1)).toLong()
                    return value or (high shl (8 * index))
                }
                value = value or (byte().toLong() shl (8 * index))
                mask = mask shr 1
            }
            return value
        }

        /** A count whose items each need at least one header byte; rejects counts the header cannot hold. */
        fun count(): Long {
            val value = number()
            if (value < 0 || value > remaining()) throw ArchiveDamaged("count exceeds header")
            return value
        }

        fun checkedLength(length: Long): Int {
            if (length < 0 || length > remaining()) throw ArchiveTruncated("field exceeds header")
            return length.toInt()
        }

        fun bytes(length: Int): ByteArray {
            checkedLength(length.toLong())
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        fun skip(length: Long) { position += checkedLength(length) }

        fun limitFor(size: Long): Int = position + checkedLength(size)

        fun peekU32(): Long? = if (remaining() >= 4) bytes.u32(position) else null

        /** Reads one NUL-terminated UTF-16LE name, keeping at most [keepChars] characters. */
        fun utf16Name(limit: Int, keepChars: Int): String? {
            val builder = if (keepChars > 0) StringBuilder() else null
            while (true) {
                if (position + 2 > limit) throw ArchiveDamaged("name list is truncated")
                val char = bytes.u16(position).toChar()
                position += 2
                if (char == '\u0000') return builder?.toString()
                if (builder != null && builder.length < keepChars) builder.append(char)
            }
        }
    }
}
