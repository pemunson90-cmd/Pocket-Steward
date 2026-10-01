package com.pocketsteward.app.image

import com.pocketsteward.app.data.db.FileRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

enum class ImageAttemptOutcome { STARTED, SUCCEEDED, UNAVAILABLE }
data class ImageAttempt(val at: Long, val outcome: ImageAttemptOutcome)

/**
 * Private scheduling hints, never evidence or file-operation authority.
 * One bounded append journal avoids tens of thousands of tiny private files.
 * A torn/corrupt tail loses only hints after the last verified record.
 */
class ImageAttemptStore(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val journal get() = File(directory, "attempts.log")
    private val entries = linkedMapOf<String, ImageAttempt>()
    private var loaded = false
    private var loadedLength = -1L
    private var loadedModified = -1L
    private var validBytes = 0L
    private var loggedRecords = 0

    private fun key(record: FileRecord, text: Boolean): String {
        val signature = listOf(record.stableRef, record.displayName, record.sizeBytes.toString(),
            record.modifiedAt?.toString().orEmpty(), record.quickFingerprint.orEmpty()).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + if (text) "1" else "0"
    }

    @Synchronized fun read(record: FileRecord, text: Boolean): ImageAttempt? {
        load()
        return entries[key(record, text)]
    }

    @Synchronized fun write(record: FileRecord, text: Boolean, outcome: ImageAttemptOutcome = ImageAttemptOutcome.STARTED): Boolean = try {
        load()
        require(directory.isDirectory || directory.mkdirs())
        val key = key(record, text)
        if (loggedRecords >= MAX_LOG_RECORDS || (entries.size >= MAX_ENTRIES && key !in entries)) compact()
        val attempt = ImageAttempt(clock().coerceAtLeast(0), outcome)
        RandomAccessFile(journal, "rw").use { file ->
            file.setLength(validBytes) // Discard an interrupted tail before appending.
            file.seek(validBytes)
            if (validBytes == 0L) file.writeInt(MAGIC)
            file.write(encode(key, attempt))
            file.fd.sync()
        }
        validBytes = journal.length()
        loadedLength = journal.length(); loadedModified = journal.lastModified()
        loggedRecords++
        entries[key] = attempt
        true
    } catch (_: Exception) { false }

    private fun load() {
        if (loaded && journal.length() == loadedLength && journal.lastModified() == loadedModified) return
        entries.clear(); validBytes = 0; loggedRecords = 0
        loaded = true
        loadedLength = journal.length(); loadedModified = journal.lastModified()
        if (!journal.isFile || journal.length() > MAX_BYTES) return
        try {
            DataInputStream(journal.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return
                validBytes = 4
                while (true) {
                    val bytes = ByteArray(RECORD_BYTES)
                    try { input.readFully(bytes) } catch (_: java.io.EOFException) { break }
                    val (key, attempt) = decode(bytes)
                    entries[key] = attempt
                    validBytes += RECORD_BYTES
                    loggedRecords++
                    require(entries.size <= MAX_ENTRIES)
                }
            }
        } catch (_: Exception) { /* Retain the verified prefix; hints are disposable. */ }
        if (entries.size > MAX_ENTRIES) { entries.clear(); validBytes = 0; loggedRecords = 0 }
    }

    private fun compact() {
        val retained = entries.entries.sortedByDescending { it.value.at }.take(MAX_ENTRIES - 1_000)
        val temporary = File(directory, "attempts.${UUID.randomUUID()}.pending")
        try {
            java.io.FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC)
                retained.forEach { output.write(encode(it.key, it.value)) }
                output.flush(); stream.fd.sync()
            }
            Files.move(temporary.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            entries.clear(); retained.forEach { entries[it.key] = it.value }
            loggedRecords = entries.size; validBytes = journal.length()
            loadedLength = journal.length(); loadedModified = journal.lastModified()
        } finally { temporary.delete() }
    }

    private fun encode(key: String, attempt: ImageAttempt): ByteArray {
        val buffer = ByteArrayOutputStream(RECORD_BYTES)
        DataOutputStream(buffer).use { output ->
            for (n in 0 until 64 step 2) output.writeByte(key.substring(n, n + 2).toInt(16))
            output.writeByte(key.last().digitToInt())
            output.writeLong(attempt.at)
            output.writeByte(attempt.outcome.ordinal)
            output.flush()
            val checksum = CRC32().apply { update(buffer.toByteArray()) }.value.toInt()
            output.writeInt(checksum)
        }
        return buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray): Pair<String, ImageAttempt> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val hash = ByteArray(32).also(input::readFully).joinToString("") { "%02x".format(it) }
        val text = input.readUnsignedByte().also { require(it in 0..1) }
        val at = input.readLong().also { require(it >= 0) }
        val outcome = ImageAttemptOutcome.entries[input.readUnsignedByte()]
        val checksum = CRC32().apply { update(bytes, 0, RECORD_BYTES - 4) }.value.toInt()
        require(input.readInt() == checksum)
        (hash + text) to ImageAttempt(at, outcome)
    }

    private companion object {
        const val MAGIC = 0x50534933
        const val RECORD_BYTES = 46
        const val MAX_ENTRIES = 64_000
        const val MAX_LOG_RECORDS = 100_000
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
