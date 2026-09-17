package com.pocketsteward.app.storage

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream

/**
 * [StorageGateway] backed by direct java.io.File access under
 * MANAGE_EXTERNAL_STORAGE (plan Section 5, Mode A).
 *
 * [stat] leaves `createdAt` null rather than reaching for
 * java.nio.file.Files' BasicFileAttributes: Android's ext4 doesn't reliably
 * track file birth time, so that call tends to just return the modification
 * time back under a different name — not worth the extra surface for a
 * value that wouldn't be trustworthy. Section 7 already marks `createdAt`
 * nullable for exactly this reason.
 */
class DirectStorageGateway(
    private val context: Context,
) : StorageGateway {

    override suspend fun rootOf(scope: StorageScope): FileRef {
        check(scope is StorageScope.Broad) {
            "DirectStorageGateway only serves StorageScope.Broad, got $scope"
        }
        return FileRef.Direct(Environment.getExternalStorageDirectory().absolutePath)
    }

    override suspend fun listChildren(directory: FileRef): List<FileEntry> {
        val dir = File(directory.requirePath())
        val children = dir.listFiles() ?: return emptyList()
        return children.map { child ->
            FileEntry(
                ref = FileRef.Direct(child.absolutePath),
                displayName = child.name,
                isDirectory = child.isDirectory,
                parentRef = directory,
            )
        }
    }

    override suspend fun stat(ref: FileRef): FileMetadata {
        val file = File(ref.requirePath())
        val extension = file.extension.lowercase()
        return FileMetadata(
            ref = ref,
            displayName = file.name,
            extension = extension,
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension),
            sizeBytes = if (file.isDirectory) 0 else file.length(),
            createdAtEpochMs = null,
            modifiedAtEpochMs = file.lastModified().takeIf { it > 0 },
            isDirectory = file.isDirectory,
            isHidden = file.isHidden || file.name.startsWith("."),
        )
    }

    override suspend fun openRead(ref: FileRef): InputStream = TODO("Milestone 2")
    override suspend fun createDirectory(parent: FileRef, name: String): FileRef = TODO("Milestone 2")
    override suspend fun move(source: FileRef, destination: FileRef): MutationResult = TODO("Milestone 2")
    override suspend fun rename(source: FileRef, newName: String): MutationResult = TODO("Milestone 2")
    override suspend fun trash(source: FileRef): MutationResult = TODO("Milestone 2/14")
}

private fun FileRef.requirePath(): String =
    (this as? FileRef.Direct)?.absolutePath
        ?: error("DirectStorageGateway received a non-Direct FileRef: $this")
