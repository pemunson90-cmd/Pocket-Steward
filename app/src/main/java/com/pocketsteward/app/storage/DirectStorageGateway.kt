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

    override suspend fun openRead(ref: FileRef): InputStream = TODO("Milestone 4 (content inspection)")

    override suspend fun createDirectory(parent: FileRef, name: String): FileRef {
        val dir = File(File(parent.requirePath()), name)
        if (dir.exists()) {
            if (dir.isDirectory) return FileRef.Direct(dir.absolutePath)
            error("Cannot create directory — a file already occupies ${dir.absolutePath}")
        }
        if (!dir.mkdirs()) error("Failed to create directory: ${dir.absolutePath}")
        return FileRef.Direct(dir.absolutePath)
    }

    override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
        moveFile(File(source.requirePath()), File(destination.requirePath()))

    override suspend fun rename(source: FileRef, newName: String): MutationResult {
        val sourceFile = File(source.requirePath())
        val destinationFile = File(sourceFile.parentFile, newName)
        return moveFile(sourceFile, destinationFile)
    }

    /**
     * Moves [source] into an app-managed Trash root that mirrors its
     * original location relative to external storage (plan Section 14 /
     * Decision 6): never a real delete, and there is deliberately no
     * operation anywhere in this app that empties Trash — that's left as a
     * manual, outside-the-app action.
     */
    override suspend fun trash(source: FileRef): MutationResult {
        val sourceFile = File(source.requirePath())
        if (!sourceFile.exists()) return MutationResult.Failure("Source does not exist: ${sourceFile.absolutePath}")

        val externalRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val sourcePath = sourceFile.absolutePath
        if (!sourcePath.startsWith("$externalRoot/")) {
            return MutationResult.Failure("Source is outside external storage, can't compute a Trash path: $sourcePath")
        }
        val relativePath = sourcePath.removePrefix("$externalRoot/")
        val destinationFile = File(File(externalRoot, TRASH_RELATIVE_ROOT), relativePath)
        return moveFile(sourceFile, destinationFile)
    }

    override suspend fun removeIfEmpty(ref: FileRef): MutationResult {
        val dir = File(ref.requirePath())
        if (!dir.exists()) {
            // Already gone — undoing an undo, or someone removed it by
            // hand. Nothing left to do, and that's success, not failure.
            return MutationResult.Success(ref)
        }
        if (!dir.isDirectory) return MutationResult.Failure("Not a directory: ${dir.absolutePath}")
        val children = dir.listFiles()
        if (children != null && children.isNotEmpty()) {
            return MutationResult.Failure("Directory is not empty, leaving it in place: ${dir.absolutePath}")
        }
        return if (dir.delete()) {
            MutationResult.Success(ref)
        } else {
            MutationResult.Failure("Could not remove directory: ${dir.absolutePath}")
        }
    }

    private fun moveFile(sourceFile: File, destinationFile: File): MutationResult {
        if (!sourceFile.exists()) return MutationResult.Failure("Source does not exist: ${sourceFile.absolutePath}")
        if (destinationFile.exists()) return MutationResult.Failure("Destination already exists: ${destinationFile.absolutePath}")

        val destinationParent = destinationFile.parentFile
        if (destinationParent != null && !destinationParent.exists() && !destinationParent.mkdirs()) {
            return MutationResult.Failure("Could not create parent directory: ${destinationParent.absolutePath}")
        }

        if (sourceFile.renameTo(destinationFile)) {
            return MutationResult.Success(FileRef.Direct(destinationFile.absolutePath))
        }

        // renameTo fails across filesystem boundaries (e.g. internal storage
        // to an SD card) even when both are under MANAGE_EXTERNAL_STORAGE.
        // Fall back to copy-then-delete.
        return try {
            sourceFile.copyTo(destinationFile, overwrite = false)
            if (!sourceFile.delete()) {
                // Data isn't lost — the copy landed — but leaving the
                // original in place rather than silently discarding it
                // means the caller sees a failure and the file exists
                // twice, which is safer than the alternative of quietly
                // losing track of which copy is authoritative.
                return MutationResult.Failure("Copied to destination but could not remove the original: ${sourceFile.absolutePath}")
            }
            MutationResult.Success(FileRef.Direct(destinationFile.absolutePath))
        } catch (t: Throwable) {
            MutationResult.Failure("Copy+delete fallback failed: ${t.message}", t)
        }
    }

    private companion object {
        const val TRASH_RELATIVE_ROOT = "PocketSteward/Trash"
    }
}

private fun FileRef.requirePath(): String =
    (this as? FileRef.Direct)?.absolutePath
        ?: error("DirectStorageGateway received a non-Direct FileRef: $this")
