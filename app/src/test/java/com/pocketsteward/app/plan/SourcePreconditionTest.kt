package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import org.junit.Test
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.FileEntry
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.io.File

class SourcePreconditionTest {
    @Test
    fun nestedFileChangeIsDetectedEvenWhenRootTimestampIsUnchanged() = runBlocking {
        val root = Files.createTempDirectory("reviewed-folder").toFile()
        try {
            val nested = File(root, "Notes").apply { mkdir() }
            val note = File(nested, "note.txt").apply { writeText("before") }
            val rootTime = root.lastModified()
            val gateway = Proxy.newProxyInstance(StorageGateway::class.java.classLoader, arrayOf(StorageGateway::class.java)) { _, method, args ->
                val ref = args[0] as FileRef.Direct
                val file = File(ref.absolutePath)
                when (method.name) {
                    "stat" -> FileMetadata(ref, file.name, file.extension, null, file.length(), null, file.lastModified(), file.isDirectory, file.isHidden)
                    "listChildren" -> file.listFiles()!!.map { FileEntry(FileRef.Direct(it.path), it.name, it.isDirectory, ref) }
                    else -> error("Unexpected gateway method: ${method.name}")
                }
            } as StorageGateway
            val before = SourcePreconditions.capture(gateway, FileRef.Direct(root.path))
            note.writeText("after, with extra content")
            root.setLastModified(rootTime)
            val after = SourcePreconditions.capture(gateway, FileRef.Direct(root.path))
            assertThat(before.directoryEntryCount).isEqualTo(2)
            assertThat(SourcePreconditions.matches(before, after)).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changedFolderContentsAndTypeFailClosed() {
        val folder = SourcePrecondition(100L, 200L, "reviewed-tree", 3)
        assertThat(SourcePreconditions.matches(folder, folder.copy(directoryDigest = "changed-tree"))).isFalse()
        assertThat(SourcePreconditions.matches(folder, folder.copy(directoryEntryCount = 4))).isFalse()
        assertThat(SourcePreconditions.matches(folder, SourcePrecondition(100L, 200L))).isFalse()
    }

    @Test
    fun exactMetadataMatches() {
        val expected = SourcePrecondition(100L, 200L)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(100L, 200L))).isTrue()
    }

    @Test
    fun sizeChangeFailsClosed() {
        val expected = SourcePrecondition(100L, 200L)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(101L, 200L))).isFalse()
    }

    @Test
    fun modificationTimeChangeFailsClosedWhenKnown() {
        val expected = SourcePrecondition(100L, 200L)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(100L, 201L))).isFalse()
    }

    @Test
    fun unknownApprovedModificationTimeFallsBackToSize() {
        val expected = SourcePrecondition(100L, null)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(100L, 999L))).isTrue()
    }

    @Test
    fun directoryMetadataDoesNotCreateAFilePrecondition() {
        val metadata = FileMetadata(
            ref = FileRef.Direct("/sd/Folder"),
            displayName = "Folder",
            extension = "",
            mimeType = null,
            sizeBytes = 0L,
            createdAtEpochMs = null,
            modifiedAtEpochMs = 123L,
            isDirectory = true,
            isHidden = false,
        )
        assertThat(SourcePreconditions.from(metadata)).isNull()
    }

    @Test
    fun indexedFileRecordBecomesApprovalPrecondition() {
        val record = FileRecord(
            stableRef = "/sd/a.txt",
            displayName = "a.txt",
            extension = "txt",
            mimeType = "text/plain",
            absolutePathOrUri = "/sd/a.txt",
            parentRef = "/sd",
            sizeBytes = 55L,
            createdAt = null,
            modifiedAt = 77L,
            lastScannedAt = 88L,
            isDirectory = false,
            isHidden = false,
        )
        assertThat(SourcePreconditions.from(record)).isEqualTo(SourcePrecondition(55L, 77L))
    }
}
