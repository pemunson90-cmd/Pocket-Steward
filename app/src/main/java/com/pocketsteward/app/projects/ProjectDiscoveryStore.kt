package com.pocketsteward.app.projects

import com.pocketsteward.app.data.db.IndexedProjectLayout
import java.io.*
import java.security.MessageDigest

/** Append-only derived page checkpoints. A torn tail loses only its unfinished page. */
class ProjectDiscoveryStore(private val directory: File) {
    data class Snapshot(val rows: List<IndexedProjectLayout>, val complete: Boolean, val validBytes: Long)
    private val maxBytes = 32L * 1024 * 1024
    private fun file(key: String): File = File(directory, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".pages")
    @Synchronized fun load(key: String): Snapshot {
        val file = file(key)
        if (!file.isFile || file.length() > maxBytes) return Snapshot(emptyList(), false, 0)
        val rows = mutableListOf<IndexedProjectLayout>()
        var valid = 0L
        var complete = false
        try {
            RandomAccessFile(file, "r").use { input ->
                require(input.readInt() == 0x50534431 && input.readUTF() == key)
                valid = input.filePointer
                while (input.filePointer < input.length()) {
                    val size = input.readInt().also { require(it in 4..1_048_576) }
                    val checksum = ByteArray(32).also(input::readFully)
                    val payload = ByteArray(size).also(input::readFully)
                    require(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload)))
                    val page = DataInputStream(ByteArrayInputStream(payload)).use { decoded ->
                        val count = decoded.readInt().also { require(it in 0..200) }
                        List(count) { IndexedProjectLayout(decoded.readUTF(), decoded.readUTF(), decoded.readBoolean(), decoded.readUTF()) }
                            .also { require(decoded.available() == 0) }
                    }
                    require(!complete && rows.size + page.size <= 100_000)
                    require(page.map { it.path } == page.map { it.path }.distinct().sortedWith(ProjectDiscoveryOrder))
                    require(rows.isEmpty() || page.isEmpty() || ProjectDiscoveryOrder.compare(page.first().path, rows.last().path) > 0)
                    rows += page
                    complete = page.isEmpty()
                    valid = input.filePointer
                }
            }
        } catch (_: Exception) { /* Read-only observations in fully verified pages remain reusable. */ }
        return Snapshot(rows, complete, valid)
    }
    @Synchronized fun append(key: String, page: List<IndexedProjectLayout>) {
        require(directory.isDirectory || directory.mkdirs())
        val saved = load(key)
        require(!saved.complete && saved.rows.size + page.size <= 100_000)
        require(page.size <= 200 && page.map { it.path } == page.map { it.path }.distinct().sortedWith(ProjectDiscoveryOrder))
        require(saved.rows.isEmpty() || page.isEmpty() || ProjectDiscoveryOrder.compare(page.first().path, saved.rows.last().path) > 0)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(page.size)
            page.forEach { out.writeUTF(it.path); out.writeUTF(it.name); out.writeBoolean(it.hidden); out.writeUTF(it.observedRoles) }
        }
        val payload = buffer.toByteArray()
        require(payload.size <= 1_048_576 && saved.validBytes + payload.size + 36 <= maxBytes)
        RandomAccessFile(file(key), "rw").use { out ->
            out.setLength(saved.validBytes)
            out.seek(saved.validBytes)
            if (saved.validBytes == 0L) { out.writeInt(0x50534431); out.writeUTF(key) }
            out.writeInt(payload.size); out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload)
            out.fd.sync()
        }
        directory.listFiles()?.filter { it.name.endsWith(".pages") && it != file(key) }
            ?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }
}
