package com.pocketsteward.app.metadata

import com.pocketsteward.app.data.db.FileRecord
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Rebuildable private cache; never holds filesystem mutation authority.
 *
 * Archive results are additionally keyed by [archiveInspectorRevision], so an entry
 * written by an older inspector (for example "unsupported" before RAR/7z listing)
 * is never reused as current support. Other entries keep their existing keys.
 * A null revision reproduces the pre-revision key and exists for migration tests.
 */
class MetadataEvidenceCache(
    private val directory: File,
    private val archiveInspectorRevision: Int? = ArchiveInspector.REVISION,
) {
    fun read(record: FileRecord, fingerprint: String?): MetadataEnrichment? = runCatching {
        if (record.modifiedAt == null || fingerprint == null) return null // Unknown freshness is not proof of reuse.
        val file = fileFor(record)
        if (!file.isFile || file.length() > 1_048_576) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 0x50534D32 && input.readInt() == 4)
            require(input.readUTF() == record.stableRef && input.readLong() == record.sizeBytes && input.readLong() == record.modifiedAt)
            require(input.readUTF() == derivationKey(record, fingerprint))
            val updated = record.copy(
                mediaType = input.string(), width = input.integer(), height = input.integer(), durationMs = input.long(),
                apkPackageName = input.string(), apkVersionName = input.string(),
            )
            val pages = input.integer()
            val entries = input.integer()
            val label = input.string()
            val version = input.long()
            val camera = input.string()
            val orientation = input.string()
            val date = input.string()
            val artist = input.string()
            val album = input.string()
            val title = input.string()
            val complete = input.string()?.toBooleanStrict()
            val note = input.string()
            val count = input.readInt()
            require(count in 0..100)
            val sample = List(count) { input.readUTF() }
            require(input.read() == -1)
            MetadataEnrichment(updated, updated != record, pdfPageCount = pages, archiveEntryCount = entries,
                archiveSample = sample, archiveComplete = complete, archiveNote = note, apkLabel = label, apkVersionCode = version, exifCamera = camera, exifOrientation = orientation, captureDate = date, mediaArtist = artist, mediaAlbum = album, mediaTitle = title)
        }
    }.getOrNull()

    fun write(result: MetadataEnrichment, fingerprint: String?) {
        if (result.record.modifiedAt == null || fingerprint == null) return
        var temporary: File? = null
        try {
            directory.mkdirs()
            val target = fileFor(result.record)
            temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                val record = result.record
                output.writeInt(0x50534D32); output.writeInt(4)
                output.writeUTF(record.stableRef); output.writeLong(record.sizeBytes); output.writeLong(requireNotNull(record.modifiedAt))
                output.writeUTF(derivationKey(record, fingerprint))
                output.string(record.mediaType); output.integer(record.width); output.integer(record.height); output.long(record.durationMs)
                output.string(record.apkPackageName); output.string(record.apkVersionName)
                output.integer(result.pdfPageCount); output.integer(result.archiveEntryCount); output.string(result.apkLabel); output.long(result.apkVersionCode)
                output.string(result.exifCamera); output.string(result.exifOrientation)
                output.string(result.captureDate); output.string(result.mediaArtist); output.string(result.mediaAlbum); output.string(result.mediaTitle)
                output.string(result.archiveComplete?.toString()); output.string(result.archiveNote)
                val samples = result.archiveSample.take(100)
                output.writeInt(samples.size); samples.forEach(output::writeUTF)
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // A cache failure cannot prevent inspection or modify user files.
        } finally { temporary?.delete() }
    }

    private fun derivationKey(record: FileRecord, fingerprint: String): String =
        if (archiveInspectorRevision != null && record.extension.lowercase() in ArchiveInspector.supportedExtensions) {
            "$fingerprint|archive-inspector:$archiveInspectorRevision"
        } else fingerprint

    private fun fileFor(record: FileRecord): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(record.stableRef.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.bin")
    }
}

private fun DataInputStream.string(): String? = if (readBoolean()) readUTF() else null
private fun DataInputStream.integer(): Int? = if (readBoolean()) readInt() else null
private fun DataInputStream.long(): Long? = if (readBoolean()) readLong() else null
private fun DataOutputStream.string(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
private fun DataOutputStream.integer(value: Int?) { writeBoolean(value != null); if (value != null) writeInt(value) }
private fun DataOutputStream.long(value: Long?) { writeBoolean(value != null); if (value != null) writeLong(value) }
