package com.pocketsteward.app.evidence

import com.google.common.truth.Truth.assertThat
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvidenceFingerprintTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun smallDocumentBytesChangingWithSameLengthAreDetected() {
        val before = "Project: Lilith".toByteArray()
        val after = "Project: NSTL!!".toByteArray()
        assertThat(before.size).isEqualTo(after.size)
        assertThat(EvidenceFingerprint.read(before.inputStream(), before.size.toLong()))
            .isNotEqualTo(EvidenceFingerprint.read(after.inputStream(), after.size.toLong()))
    }
    @Test fun nonseekableProviderNeverDrainsALargeFile() {
        var readBytes = 0
        val stream = object : InputStream() {
            override fun read(): Int { check(++readBytes <= EvidenceFingerprint.WINDOW_BYTES); return 65 }
            override fun skip(n: Long): Long = error("Provider streams must never be drained to reach a tail")
        }
        assertThat(EvidenceFingerprint.read(stream, 2_000_000_000L)).startsWith(EvidenceFingerprint.PREFIX + "prefix:")
        assertThat(readBytes).isEqualTo(EvidenceFingerprint.WINDOW_BYTES)
    }
    @Test fun seekableLargeFileReadsAtMostTwoWindowsAndDetectsTailChanges() {
        val file = temporary.newFile().apply { writeBytes(ByteArray(100_000) { 65 }) }
        var readBytes = 0
        val before = object : FileInputStream(file) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) readBytes += it }
        }.use { EvidenceFingerprint.read(it, file.length()) }
        assertThat(readBytes).isEqualTo(EvidenceFingerprint.MAX_READ_BYTES)
        val modified = file.readBytes().apply { this[lastIndex] = 66 }
        file.writeBytes(modified)
        val after = file.inputStream().use { EvidenceFingerprint.read(it, file.length()) }
        assertThat(after).isNotEqualTo(before)
        assertThat(after).startsWith(EvidenceFingerprint.PREFIX + "prefix-tail:")
    }
    @Test fun boundedSampleDoesNotClaimAWholeFileIntegrityProof() {
        val file = temporary.newFile().apply { writeBytes(ByteArray(100_000) { 65 }) }
        val before = file.inputStream().use { EvidenceFingerprint.read(it, file.length()) }
        file.writeBytes(file.readBytes().apply { this[50_000] = 66 })
        val after = file.inputStream().use { EvidenceFingerprint.read(it, file.length()) }
        assertThat(after).isEqualTo(before) // Untouched windows: explicit sampling limitation.
    }
    @Test fun cancellationPropagatesBeforeAnotherRead() {
        var reads = 0
        val stream = object : InputStream() { override fun read(): Int { reads++; return 65 } }
        val failure = runCatching { EvidenceFingerprint.read(stream, 10_000) { throw CancellationException("Stop") } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(reads).isEqualTo(0)
    }
}
