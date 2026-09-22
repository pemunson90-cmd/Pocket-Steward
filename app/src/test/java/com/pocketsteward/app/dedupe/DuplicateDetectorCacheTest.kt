package com.pocketsteward.app.dedupe

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DuplicateDetectorCacheTest {
    @Test
    fun cachedQuickAndFullHashesAvoidAllFileReads() = runTest {
        val bytes = "same content".toByteArray()
        val quick = "cached-quick"
        val full = "cached-sha"
        val a = record("/root/a.txt", bytes.size.toLong()).copy(
            quickFingerprint = quick,
            sha256 = full,
        )
        val b = record("/root/b.txt", bytes.size.toLong()).copy(
            quickFingerprint = quick,
            sha256 = full,
        )
        val gateway = CountingGateway(
            mapOf(
                FileRef.Direct(a.stableRef) to bytes,
                FileRef.Direct(b.stableRef) to bytes,
            ),
        )

        val groups = DuplicateDetector(gateway).findDuplicates(listOf(a, b))

        assertThat(groups).hasSize(1)
        assertThat(groups.single().members).hasSize(2)
        assertThat(gateway.readCount).isEqualTo(0)
    }

    @Test
    fun cachedFullHashStillAvoidsSecondFullReadAfterQuickFingerprinting() = runTest {
        val bytes = "same content".toByteArray()
        val full = "cached-sha"
        val a = record("/root/a.txt", bytes.size.toLong()).copy(sha256 = full)
        val b = record("/root/b.txt", bytes.size.toLong()).copy(sha256 = full)
        val gateway = CountingGateway(
            mapOf(
                FileRef.Direct(a.stableRef) to bytes,
                FileRef.Direct(b.stableRef) to bytes,
            ),
        )

        val groups = DuplicateDetector(gateway).findDuplicates(listOf(a, b))

        assertThat(groups).hasSize(1)
        // One short quick-fingerprint read per candidate. Full SHA reads are
        // skipped because the scan cache still matches this FileRecord.
        assertThat(gateway.readCount).isEqualTo(2)
    }

    private fun record(path: String, size: Long) = FileRecord(
        stableRef = path,
        displayName = path.substringAfterLast('/'),
        extension = "txt",
        mimeType = "text/plain",
        absolutePathOrUri = path,
        parentRef = path.substringBeforeLast('/'),
        sizeBytes = size,
        createdAt = null,
        modifiedAt = 123L,
        lastScannedAt = 456L,
        isDirectory = false,
        isHidden = false,
    )

    private class CountingGateway(
        private val bytes: Map<FileRef, ByteArray>,
    ) : StorageGateway {
        var readCount = 0

        override suspend fun rootOf(scope: StorageScope): FileRef = FileRef.Direct("/root")
        override suspend fun listChildren(directory: FileRef): List<FileEntry> = emptyList()

        override suspend fun stat(ref: FileRef): FileMetadata {
            val body = bytes[ref] ?: error("missing")
            return FileMetadata(
                ref = ref,
                displayName = (ref as FileRef.Direct).absolutePath.substringAfterLast('/'),
                extension = "txt",
                mimeType = "text/plain",
                sizeBytes = body.size.toLong(),
                createdAtEpochMs = null,
                modifiedAtEpochMs = 123L,
                isDirectory = false,
                isHidden = false,
            )
        }

        override suspend fun exists(ref: FileRef): Boolean = ref in bytes

        override suspend fun openRead(ref: FileRef): InputStream {
            readCount++
            return ByteArrayInputStream(bytes[ref] ?: error("missing"))
        }

        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult =
            error("not used")
        override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult =
            error("not used")
        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult =
            error("not used")
        override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
            error("not used")
        override suspend fun rename(source: FileRef, newName: String): MutationResult =
            error("not used")
        override suspend fun trashDestination(source: FileRef): FileRef =
            error("not used")
        override suspend fun trash(source: FileRef): MutationResult =
            error("not used")
        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult =
            error("not used")
    }
}
