package com.pocketsteward.app.evidence.analysis

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageAccessMode
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Requests are written once; only a tiny cursor is rewritten after each file. */
class EvidenceAnalysisStore(private val directory: File) {
    private val maxBytes = 32 * 1024 * 1024
    private fun path(id: String, suffix: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.$suffix")
    }
    @Synchronized fun saveRequest(request: EvidenceAnalysisRequest) {
        request.validate()
        require(!path(request.id, "request").exists()) { "Analysis request already exists." }
        write(path(request.id, "request")) { out ->
            out.writeInt(1); out.writeUTF(request.id); out.writeUTF(request.mode.name); out.optional(request.grant)
            out.writeBoolean(request.images); out.writeBoolean(request.content); out.writeBoolean(request.retryUnavailable)
            out.writeInt(request.sources.size)
            request.sources.forEach { source ->
                val r = source.record
                out.writeUTF(source.sourceRoot); out.writeUTF(r.stableRef); out.writeUTF(r.displayName)
                out.writeUTF(r.extension); out.optional(r.mimeType); out.optional(r.parentRef)
                out.writeLong(r.sizeBytes); out.writeBoolean(r.modifiedAt != null); r.modifiedAt?.let { out.writeLong(it) }
            }
        }
        saveProgress(EvidenceAnalysisProgress(request.id, EvidenceAnalysisStatus.QUEUED, request.sources.size))
        write(File(directory, "latest")) { it.writeUTF(request.id) }
    }
    @Synchronized fun request(id: String): EvidenceAnalysisRequest = read(path(id, "request")) { input ->
        require(input.readInt() == 1)
        val storedId = input.readUTF(); require(storedId == id)
        val mode = StorageAccessMode.valueOf(input.readUTF()); val grant = input.optional()
        val images = input.readBoolean(); val content = input.readBoolean(); val retry = input.readBoolean()
        val count = input.readInt().also { require(it in 1..100_000) }
        EvidenceAnalysisRequest(id, mode, grant, images, content, retry, List(count) {
            val root = input.readUTF(); val ref = input.readUTF(); val name = input.readUTF(); val extension = input.readUTF()
            val mime = input.optional(); val parent = input.optional(); val size = input.readLong()
            val modified = if (input.readBoolean()) input.readLong() else null
            EvidenceAnalysisSource(FileRecord(stableRef = ref, displayName = name, extension = extension, mimeType = mime,
                absolutePathOrUri = ref, parentRef = parent, sizeBytes = size, createdAt = null, modifiedAt = modified,
                lastScannedAt = 0, isDirectory = false, isHidden = name.startsWith('.')), root)
        }).also { it.validate() }
    }
    @Synchronized fun latest(): EvidenceAnalysisProgress? {
        if (!File(directory, "latest").exists()) return null
        val id = read(File(directory, "latest")) { it.readUTF() }
        return progress(id)
    }
    @Synchronized fun progress(id: String): EvidenceAnalysisProgress = read(path(id, "progress")) { input ->
        require(input.readInt() == 1 && input.readUTF() == id)
        EvidenceAnalysisProgress(id, EvidenceAnalysisStatus.valueOf(input.readUTF()), input.readInt(), input.readInt(),
            input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readBoolean(), input.readUTF(), input.readLong())
    }
    @Synchronized fun update(id: String, change: (EvidenceAnalysisProgress) -> EvidenceAnalysisProgress): EvidenceAnalysisProgress {
        val next = change(progress(id))
        require(next.id == id)
        saveProgress(next)
        return next
    }
    private fun saveProgress(progress: EvidenceAnalysisProgress) = write(path(progress.id, "progress")) { out ->
        out.writeInt(1); out.writeUTF(progress.id); out.writeUTF(progress.status.name); out.writeInt(progress.total); out.writeInt(progress.processed)
        out.writeInt(progress.analyzed); out.writeInt(progress.reused); out.writeInt(progress.partial); out.writeInt(progress.unavailable); out.writeInt(progress.changed)
        out.writeBoolean(progress.pauseRequested); out.writeUTF(progress.detail.take(4000)); out.writeLong(progress.updatedAt)
    }
    private fun <T> read(file: File, block: (DataInputStream) -> T): T {
        require(file.length() in 32..maxBytes.toLong()) { "Invalid analysis checkpoint size." }
        val bytes = file.readBytes()
        val payload = bytes.copyOfRange(32, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) { "Analysis checkpoint is corrupt." }
        return DataInputStream(ByteArrayInputStream(payload)).use { input -> block(input).also { require(input.available() == 0) } }
    }
    private fun write(file: File, block: (DataOutputStream) -> Unit) {
        require(directory.isDirectory || directory.mkdirs())
        val buffer = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) { require(buffer.size() < maxBytes - 32); buffer.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { require(buffer.size().toLong() + length <= maxBytes - 32); buffer.write(bytes, offset, length) }
        }
        DataOutputStream(bounded).use(block)
        val payload = buffer.toByteArray()
        val temporary = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload); out.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null
}
