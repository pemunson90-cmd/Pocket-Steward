package com.pocketsteward.app.report

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.child
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
    fun verifiedExporterAlsoWorksWithSafStyleRefs() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val parent = FileRef.Saf("content://example/tree/root/document/root")
        val result = VerifiedTextExporter.export(
            gateway = gateway,
            parent = parent,
            finalName = "inventory.json",
            content = "{\"ok\":true}\n",
        )

        assertThat(result).isEqualTo(
            ExportResult.Written("content://example/tree/root/document/root/inventory.json"),
        )
        assertThat(gateway.files["content://example/tree/root/document/root/inventory.json"])
            .isEqualTo("{\"ok\":true}\n".toByteArray())
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
            val path = ref.rawValue()
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
            files.containsKey(ref.rawValue())

        override suspend fun openRead(ref: FileRef): InputStream =
            ByteArrayInputStream(files[ref.rawValue()] ?: error("missing"))

        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult =
            MutationResult.Success(child(parent, name))

        override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult {
            val ref = child(parent, name)
            if (!pretendWriteWithoutBytes) files[ref.rawValue()] = content.toByteArray()
            return MutationResult.Success(ref)
        }

        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult =
            error("not used")

        override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
            error("not used")

        override suspend fun rename(source: FileRef, newName: String): MutationResult {
            val sourcePath = source.rawValue()
            val parentPath = sourcePath.substringBeforeLast('/')
            val destination = when (source) {
                is FileRef.Direct -> FileRef.Direct("$parentPath/$newName")
                is FileRef.Saf -> FileRef.Saf("$parentPath/$newName")
                is FileRef.Child -> source.parent.child(newName)
            }
            val bytes = files.remove(sourcePath) ?: return MutationResult.Failure("missing")
            files[destination.rawValue()] = if (corruptOnRename) "bad".toByteArray() else bytes
            return MutationResult.Success(destination)
        }

        override suspend fun trashDestination(source: FileRef): FileRef =
            FileRef.Direct("/Trash/" + source.rawValue().substringAfterLast('/'))

        override suspend fun trash(source: FileRef): MutationResult {
            files.remove(source.rawValue())
            return MutationResult.Success(source)
        }

        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult =
            MutationResult.Success(ref, changed = false)

        private fun child(parent: FileRef, name: String): FileRef =
            parent.child(name)
    }
}
