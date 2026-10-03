package com.pocketsteward.app.metadata

import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveInspectorTest {
    @Test fun tarAndCompressedTarListNamesWithoutExtractingFiles() {
        val tar = tar()
        val plain = ArchiveInspector.stream(ByteArrayInputStream(tar), "tar")
        assertThat(plain.names).containsExactly("Lilith/notes.txt", "Lilith/cover.png").inOrder()
        assertThat(plain.observedEntries).isEqualTo(2)
        assertThat(plain.complete).isTrue()
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar) } }.toByteArray()
        assertThat(ArchiveInspector.stream(ByteArrayInputStream(compressed), "tgz")).isEqualTo(plain)
    }
    @Test fun decodedZipBudgetStopsHighlyCompressedPayloadAndReportsPartialEvidence() {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("NSTL/large.txt"))
            repeat(1025) { zip.write(ByteArray(8192)) }
            zip.closeEntry()
        } }.toByteArray()
        val result = ArchiveInspector.stream(ByteArrayInputStream(bytes), "zip")
        assertThat(result.names).containsExactly("NSTL/large.txt")
        assertThat(result.complete).isFalse()
        assertThat(result.note).contains("byte limit")
    }
    @Test fun entryLimitNeverClaimsCompleteCount() {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            repeat(2001) { zip.putNextEntry(ZipEntry("file-$it")); zip.closeEntry() }
        } }.toByteArray()
        val result = ArchiveInspector.stream(ByteArrayInputStream(bytes), "zip")
        assertThat(result.observedEntries).isEqualTo(2000)
        assertThat(result.names).hasSize(40)
        assertThat(result.complete).isFalse()
    }
    @Test fun unsupportedAndDamagedContainersHaveExplicitLimitations() {
        assertThat(ArchiveInspector.stream(ByteArrayInputStream(byteArrayOf(1)), "cab").note).contains("not supported")
        // RAR/7z need positional reads; the plain stream overload never pretends to list them.
        val sevenZip = ArchiveInspector.stream(ByteArrayInputStream(byteArrayOf(1)), "7z")
        assertThat(sevenZip.complete).isFalse()
        assertThat(sevenZip.note).contains("seekable")
        assertThat(ArchiveInspector.stream(ByteArrayInputStream(byteArrayOf(1)), "tgz").complete).isFalse()
    }
    private fun tar(): ByteArray = ByteArrayOutputStream().also { out -> TarArchiveOutputStream(out).use { tar ->
        listOf("Lilith/notes.txt", "Lilith/cover.png").forEach { name ->
            val entry = TarArchiveEntry(name).apply { size = 3 }
            tar.putArchiveEntry(entry); tar.write(byteArrayOf(1, 2, 3)); tar.closeArchiveEntry()
        }
    } }.toByteArray()
}
