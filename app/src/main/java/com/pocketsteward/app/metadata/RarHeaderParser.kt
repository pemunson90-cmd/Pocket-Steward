package com.pocketsteward.app.metadata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Header-only RAR listing. Supports the RAR 1.5-4.x block format ("RAR4") and the
 * RAR 5.0+ format ("RAR5", also written by RAR 6 and 7). Packed member data is
 * skipped by seeking; nothing is decompressed, decrypted or extracted.
 *
 * RAR 1.4 and older archives, self-extracting archives whose signature is not at
 * offset 0, and future signature versions are reported as unsupported.
 */
internal object RarHeaderParser {
    private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR14_SIGNATURE = byteArrayOf(0x52, 0x45, 0x7E, 0x5E)
    private const val RAR5_MAX_HEADER = 2L * 1024 * 1024 // 3-byte vint limit from the RAR 5.0 technote.

    fun inspect(reader: BoundedArchiveReader, limits: ArchiveLimits): ArchiveInspection {
        val head = reader.readAvailable(0, 8)
        return when {
            head.startsWith(RAR5_SIGNATURE) -> Rar5(reader, limits).run()
            head.startsWith(RAR4_SIGNATURE) -> Rar4(reader, limits).run()
            head.startsWith(RAR14_SIGNATURE) -> unsupported("RAR 1.4 and older archives are not supported; only RAR 1.5-4.x and RAR 5.0+ headers can be listed.")
            head.size >= 6 && head.copyOf(6).contentEquals(RAR4_SIGNATURE.copyOf(6)) ->
                unsupported("This RAR signature version (${head.getOrNull(6)?.toInt()?.and(0xFF)}) is newer than the supported RAR4 and RAR5 formats.")
            else -> unsupported("No RAR signature at the start of the file (self-extracting, misnamed or damaged). No files were extracted.")
        }
    }

    private fun unsupported(note: String) = ArchiveInspection(0, emptyList(), false, note)

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Shared bookkeeping so partial results always carry an explicit reason. */
    private abstract class Walk(val reader: BoundedArchiveReader, val limits: ArchiveLimits, val format: String) {
        val entries = EntryCollector(limits)
        var multipart = false
        var notFirstVolume = false
        var encryptedContent = false
        var blocks = 0

        abstract fun walk(): String?

        fun run(): ArchiveInspection {
            val stop = try { walk() } catch (failure: Exception) {
                if (failure.isCancellation()) throw failure.asCancellation()
                when (failure) {
                    is ArchiveBudgetExceeded -> "Inspection byte limit reached; this is a partial archive sample."
                    is ArchiveTruncated -> if (reader.prefixOnly) "Inspection byte limit reached; this is a partial archive sample."
                        else "$format archive is truncated; only the entries before the cut were listed."
                    is ArchiveDamaged -> "$format archive is damaged (${failure.message}); only entries before the damage were listed."
                    else -> "$format archive is unreadable or damaged; only entries before the failure were listed."
                }
            }
            val notes = mutableListOf<String>()
            stop?.let(notes::add)
            if (multipart) notes += if (notFirstVolume) "This is a later volume of a multipart $format set; other volumes were not inspected, so the listing covers only this volume."
                else "This is one volume of a multipart $format set; other volumes were not inspected, so the listing covers only this volume."
            if (encryptedContent) notes += "Some member contents are encrypted; only stored names were read."
            val complete = stop == null && !multipart
            return entries.inspection(complete, if (notes.isEmpty()) null else (notes + "No files were extracted.").joinToString(" "))
        }

        fun nextBlock() {
            if (++blocks > limits.maxBlocks) throw ArchiveBudgetExceeded("Block limit reached.")
        }

        fun advance(position: Long, by: Long): Long {
            if (by < 0) throw ArchiveDamaged("negative block size")
            return try { Math.addExact(position, by) } catch (_: ArithmeticException) { throw ArchiveDamaged("block size overflow") }
        }
    }

    private class Rar5(reader: BoundedArchiveReader, limits: ArchiveLimits) : Walk(reader, limits, "RAR5") {
        override fun walk(): String? {
            var position = 8L
            while (true) {
                if (position == reader.visibleSize) throw ArchiveTruncated("missing end of archive header")
                nextBlock()
                val prefix = reader.readAvailable(position, 7)
                if (prefix.size < 5) throw ArchiveTruncated("short header")
                val sizeField = Vint(prefix, 4, prefix.size)
                val headerSize = try { sizeField.next() } catch (cut: ArchiveDamaged) {
                    if (prefix.size < 7) throw ArchiveTruncated("short header") else throw cut
                }
                if (sizeField.offset - 4 > 3 || headerSize > RAR5_MAX_HEADER) throw ArchiveDamaged("header size exceeds the RAR5 limit")
                if (headerSize < 2) throw ArchiveDamaged("header too small")
                val total = sizeField.offset + headerSize.toInt()
                val header = if (total <= prefix.size) prefix.copyOf(total) else prefix + reader.read(position + prefix.size, total - prefix.size)
                if (crc32(header, 4, total - 4) != header.u32(0)) throw ArchiveDamaged("header checksum mismatch")
                val fields = Vint(header, sizeField.offset, total)
                val type = fields.next()
                val flags = fields.next()
                val extraSize = if (flags and 0x1L != 0L) fields.next() else 0L
                val dataSize = if (flags and 0x2L != 0L) fields.next() else 0L
                if (extraSize > total - fields.offset) throw ArchiveDamaged("extra area exceeds header")
                val extraStart = total - extraSize.toInt()
                when (type) {
                    1L -> { // Main archive header.
                        val archiveFlags = fields.next()
                        if (archiveFlags and 0x1L != 0L) multipart = true
                        if (archiveFlags and 0x2L != 0L) { notFirstVolume = true; fields.next() }
                    }
                    2L -> { // File header; service headers (3) are not members.
                        if (flags and 0x8L != 0L || flags and 0x10L != 0L) multipart = true
                        val fileFlags = fields.next()
                        fields.next() // unpacked size
                        fields.next() // attributes
                        if (fileFlags and 0x2L != 0L) fields.skip(4)
                        if (fileFlags and 0x4L != 0L) fields.skip(4)
                        fields.next() // compression information
                        fields.next() // host OS
                        val nameLength = fields.next()
                        if (nameLength > extraStart - fields.offset) throw ArchiveDamaged("name exceeds header")
                        val name = utf8(header, fields.offset, nameLength.toInt())
                        if (hasEncryptionRecord(header, extraStart, total)) encryptedContent = true
                        if (!entries.add(name)) return "Entry limit reached; this is a partial archive sample."
                    }
                    4L -> return "RAR5 headers are encrypted; member names cannot be read without a password."
                    5L -> { // End of archive.
                        val endFlags = fields.next()
                        if (endFlags and 0x1L != 0L) multipart = true
                        return null
                    }
                }
                position = advance(advance(position, total.toLong()), dataSize)
                if (position > reader.visibleSize) {
                    // Packed data runs past the end: truncated file or a volume that continues elsewhere.
                    throw ArchiveTruncated("packed data extends beyond the archive")
                }
            }
        }

        private fun hasEncryptionRecord(header: ByteArray, start: Int, end: Int): Boolean {
            var at = start
            while (at < end) {
                val record = Vint(header, at, end)
                val size = record.next()
                val bodyStart = record.offset
                if (size < 1 || size > end - bodyStart) return false
                if (record.next() == 0x01L) return true
                at = bodyStart + size.toInt()
            }
            return false
        }
    }

    private class Rar4(reader: BoundedArchiveReader, limits: ArchiveLimits) : Walk(reader, limits, "RAR4") {
        override fun walk(): String? {
            var position = 7L
            while (true) {
                if (position == reader.visibleSize) {
                    if (reader.prefixOnly) throw ArchiveBudgetExceeded("staged prefix ended")
                    // RAR 1.5-2.x may omit the end block, but so does a copy cut at a block boundary.
                    return "RAR4 archive ended without an end-of-archive block; the listing may be incomplete."
                }
                nextBlock()
                val base = reader.readAvailable(position, 7)
                if (base.size < 7) throw ArchiveTruncated("short block header")
                val type = base.u8(2)
                val flags = base.u16(3)
                val size = base.u16(5)
                if (size < 7) throw ArchiveDamaged("block header too small")
                val header = if (size == 7) base else base + reader.read(position + 7, size - 7)
                val longBlock = flags and 0x8000 != 0
                if ((longBlock || type == 0x74 || type == 0x7A) && size < 11) throw ArchiveDamaged("long block without size")
                // Old AV and signature blocks do not carry a valid header CRC.
                if (type != 0x76 && type != 0x79 && (crc32(header, 2, size - 2) and 0xFFFF) != header.u16(0).toLong()) {
                    throw ArchiveDamaged("header checksum mismatch")
                }
                // File and new-style service headers always carry PACK_SIZE at offset 7.
                var dataSize = if (longBlock || type == 0x74 || type == 0x7A) header.u32(7) else 0L
                when (type) {
                    0x73 -> { // Main header.
                        if (flags and 0x0001 != 0) multipart = true
                        if (flags and 0x0080 != 0) return "RAR4 headers are encrypted; member names cannot be read without a password."
                    }
                    0x74 -> { // File header.
                        if (size < 32) throw ArchiveDamaged("file header too small")
                        var at = 32
                        if (flags and 0x0100 != 0) { // 64-bit sizes
                            if (size < 40) throw ArchiveDamaged("file header too small")
                            dataSize = dataSize or (header.u32(32) shl 32)
                            at = 40
                        }
                        if (flags and 0x0003 != 0) multipart = true
                        if (flags and 0x0001 != 0) notFirstVolume = true
                        if (flags and 0x0004 != 0) encryptedContent = true
                        val nameSize = header.u16(26)
                        if (nameSize > size - at) throw ArchiveDamaged("name exceeds header")
                        val name = rar4Name(header, at, nameSize, flags and 0x0200 != 0)
                        if (!entries.add(name)) return "Entry limit reached; this is a partial archive sample."
                    }
                    0x7A -> if (flags and 0x0100 != 0 && size >= 36) dataSize = dataSize or (header.u32(32) shl 32)
                    0x7B -> { // End of archive.
                        if (flags and 0x0001 != 0) multipart = true
                        return null
                    }
                }
                position = advance(advance(position, size.toLong()), dataSize)
                if (position > reader.visibleSize) throw ArchiveTruncated("packed data extends beyond the archive")
            }
        }

        private fun rar4Name(header: ByteArray, at: Int, length: Int, unicode: Boolean): String {
            val raw = header.copyOfRange(at, at + length)
            val zero = raw.indexOf(0)
            val name = when {
                unicode && zero < 0 -> utf8(raw, 0, raw.size)
                unicode -> decodeRar4Unicode(raw.copyOf(zero), raw.copyOfRange(zero + 1, raw.size))
                else -> strictUtf8(raw) ?: String(raw, StandardCharsets.ISO_8859_1)
            }
            // RAR 1.5-4.x stores '\\' as the path separator for every host OS (unrar converts it).
            return name.replace('\\', '/')
        }
    }

    /** RAR 2.9-4.x compact Unicode name encoding (see unrar's EncodeFileName::Decode). */
    internal fun decodeRar4Unicode(name: ByteArray, encoded: ByteArray): String {
        val maxChars = 2048
        val out = StringBuilder()
        var pos = 0
        val highByte = if (pos < encoded.size) encoded[pos++].toInt() and 0xFF else 0
        var flags = 0
        var flagBits = 0
        fun nameByte(index: Int) = if (index < name.size) name[index].toInt() and 0xFF else 0
        while (pos < encoded.size && out.length < maxChars) {
            if (flagBits == 0) { flags = encoded[pos++].toInt() and 0xFF; flagBits = 8 }
            when (flags shr 6) {
                0 -> { if (pos >= encoded.size) break; out.append((encoded[pos++].toInt() and 0xFF).toChar()) }
                1 -> { if (pos >= encoded.size) break; out.append(((encoded[pos++].toInt() and 0xFF) + (highByte shl 8)).toChar()) }
                2 -> {
                    if (pos + 1 >= encoded.size) break
                    out.append(((encoded[pos].toInt() and 0xFF) + ((encoded[pos + 1].toInt() and 0xFF) shl 8)).toChar()); pos += 2
                }
                else -> {
                    if (pos >= encoded.size) break
                    var length = encoded[pos++].toInt() and 0xFF
                    if (length and 0x80 != 0) {
                        if (pos >= encoded.size) break
                        val correction = encoded[pos++].toInt() and 0xFF
                        length = (length and 0x7F) + 2
                        while (length-- > 0 && out.length < maxChars) out.append((((nameByte(out.length) + correction) and 0xFF) + (highByte shl 8)).toChar())
                    } else {
                        length += 2
                        while (length-- > 0 && out.length < maxChars) out.append(nameByte(out.length).toChar())
                    }
                }
            }
            flags = (flags shl 2) and 0xFF
            flagBits -= 2
        }
        return out.toString()
    }

    private fun utf8(bytes: ByteArray, offset: Int, length: Int) = String(bytes, offset, length, StandardCharsets.UTF_8)

    private fun strictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    /** RAR5 variable-length integer cursor bounded to [end]. */
    private class Vint(private val bytes: ByteArray, var offset: Int, private val end: Int) {
        fun next(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (offset >= end) throw ArchiveDamaged("field runs past header")
                val byte = bytes[offset++].toInt() and 0xFF
                if (shift > 63) throw ArchiveDamaged("integer too long")
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            if (value < 0) throw ArchiveDamaged("integer out of range")
            return value
        }

        fun skip(count: Int) {
            if (count > end - offset) throw ArchiveDamaged("field runs past header")
            offset += count
        }
    }
}
