package com.pocketsteward.app.report

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Test

class VerifiedManifestExporterTest {
    @Test
    fun successRequiresFinalArtifactToBeReadableAndExact() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val result = VerifiedManifestExporter.export(
            gateway = gateway,
            parent = FileRef.Direct("/Download"),
            document = document(),
            exportedAtEpochMs = 123L,
        )

        assertThat(result).isEqualTo(
            ExportResult.Written("/Download/POCKETSTEWARD-MANIFEST-task5-123.md"),
        )
        assertThat(gateway.files["/Download/POCKETSTEWARD-MANIFEST-task5-123.md"])
            .isEqualTo(document().text.toByteArray())
    }

    @Test
    fun falsePositiveGatewayWriteIsRejected() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway(pretendWriteWithoutBytes = true)
        val result = VerifiedManifestExporter.export(
            gateway = gateway,
            parent = FileRef.Direct("/Download"),
            document = document(),
            exportedAtEpochMs = 123L,
        )

        assertThat(result).isInstanceOf(ExportResult.Failed::class.java)
    }

    @Test
    fun corruptedFinalArtifactIsRejected() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway(corruptOnRename = true)
        val result = VerifiedManifestExporter.export(
            gateway = gateway,
            parent = FileRef.Direct("/Download"),
            document = document(),
            exportedAtEpochMs = 123L,
        )

        assertThat(result).isInstanceOf(ExportResult.Failed::class.java)
    }

    private fun document() = TaskManifestDocument(
        taskRunId = 5,
        title = "Task",
        text = "# Task\nhello\n",
        assertion = DuplicateAssertion(groups = 0, kept = 0, trashed = 0),
        entries = emptyList(),
    )

    private class FakeGateway(
        private val pretendWriteWithoutBytes: Boolean = false,
        private val corruptOnRename: Boolean = false,
    ) : StorageGateway {
        val files = linkedMapOf<String, ByteArray>()

        override suspend fun rootOf(scope: StorageScope): FileRef = FileRef.Direct("/")
        override suspend fun listChildren(directory: FileRef): List<FileEntry> = emptyList()

        override suspend fun stat(ref: FileRef): FileMetadata {
            val path = (ref as FileRef.Direct).absolutePath
            val bytes = files[path] ?: error("missing")
            return FileMetadata(
                ref = ref,
                displayName = path.substringAfterLast('/'),
                extension = path.substringAfterLast('.', ""),
                mimeType = null,
                sizeBytes = bytes.size.toLong(),
                createdAtEpochMs = null,
                modifiedAtEpochMs = null,
                isDirectory = false,
                isHidden = false,
            )
        }

        override suspend fun exists(ref: FileRef): Boolean =
            files.containsKey((ref as FileRef.Direct).absolutePath)

        override suspend fun openRead(ref: FileRef): InputStream =
            ByteArrayInputStream(files[(ref as FileRef.Direct).absolutePath] ?: error("missing"))

        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult =
            MutationResult.Success(FileRef.Direct((parent as FileRef.Direct).absolutePath + "/" + name))

        override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult {
            val path = (parent as FileRef.Direct).absolutePath.trimEnd('/') + "/" + name
            if (!pretendWriteWithoutBytes) files[path] = content.toByteArray()
            return MutationResult.Success(FileRef.Direct(path))
        }

        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult =
            error("not used")

        override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
            error("not used")

        override suspend fun rename(source: FileRef, newName: String): MutationResult {
            val sourcePath = (source as FileRef.Direct).absolutePath
            val parent = sourcePath.substringBeforeLast('/')
            val destinationPath = "$parent/$newName"
            val bytes = files.remove(sourcePath) ?: return MutationResult.Failure("missing")
            files[destinationPath] = if (corruptOnRename) "bad".toByteArray() else bytes
            return MutationResult.Success(FileRef.Direct(destinationPath))
        }

        override suspend fun trashDestination(source: FileRef): FileRef =
            FileRef.Direct("/Trash/" + (source as FileRef.Direct).absolutePath.substringAfterLast('/'))

        override suspend fun trash(source: FileRef): MutationResult {
            files.remove((source as FileRef.Direct).absolutePath)
            return MutationResult.Success(source)
        }

        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult =
            MutationResult.Success(ref, changed = false)
    }
}
