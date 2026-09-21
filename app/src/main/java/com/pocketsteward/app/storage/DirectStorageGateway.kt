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

    override suspend fun exists(ref: FileRef): Boolean = File(ref.requirePath()).exists()

    override suspend fun openRead(ref: FileRef): InputStream {
        val file = File(ref.requirePath())
        check(file.exists() && !file.isDirectory) { "Cannot open a non-existent file or a directory for reading: ${file.absolutePath}" }
        return file.inputStream()
    }

    override suspend fun createDirectory(parent: FileRef, name: String): MutationResult {
        val dir = File(File(parent.requirePath()), name)
        if (dir.exists()) {
            return if (dir.isDirectory) {
                MutationResult.Success(FileRef.Direct(dir.absolutePath), changed = false)
            } else {
                MutationResult.Failure("Cannot create directory — a file already occupies ${dir.absolutePath}")
            }
        }
        if (!dir.mkdirs()) return MutationResult.Failure("Failed to create directory: ${dir.absolutePath}")
        return MutationResult.Success(FileRef.Direct(dir.absolutePath), changed = true)
    }

    override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult {
        val target = File(File(parent.requirePath()), name)
        if (target.exists()) {
            return MutationResult.Failure("Refusing to overwrite an existing file: ${target.absolutePath}")
        }
        val parentDir = target.parentFile
            ?: return MutationResult.Failure("Cannot determine parent directory for ${target.absolutePath}")
        if (!parentDir.isDirectory) {
            return MutationResult.Failure("Parent is not a directory: ${parentDir.absolutePath}")
        }
        return try {
            target.writeText(content)
            MutationResult.Success(FileRef.Direct(target.absolutePath), changed = true)
        } catch (t: Throwable) {
            MutationResult.Failure(t.message ?: "Failed to write ${target.absolutePath}", t)
        }
    }

    override suspend fun copy(source: FileRef, destination: FileRef): MutationResult {
        val sourceFile = File(source.requirePath())
        val destinationFile = File(destination.requirePath())
        if (!sourceFile.exists()) {
            return MutationResult.Failure("Source does not exist: ${sourceFile.absolutePath}")
        }
        if (sourceFile.isDirectory) {
            return MutationResult.Failure("Directory copy is not supported by this operation: ${sourceFile.absolutePath}")
        }
        if (destinationFile.exists()) {
            return MutationResult.Failure("Destination already exists: ${destinationFile.absolutePath}")
        }
        val parent = destinationFile.parentFile
            ?: return MutationResult.Failure("Cannot determine destination parent: ${destinationFile.absolutePath}")
        if (!parent.exists() && !parent.mkdirs()) {
            return MutationResult.Failure("Could not create parent directory: ${parent.absolutePath}")
        }
        return try {
            sourceFile.copyTo(destinationFile, overwrite = false)
            if (destinationFile.length() != sourceFile.length()) {
                destinationFile.delete()
                MutationResult.Failure("Copied file size did not match source; partial destination was removed.")
            } else {
                MutationResult.Success(FileRef.Direct(destinationFile.absolutePath))
            }
        } catch (t: Throwable) {
            destinationFile.takeIf { it.exists() }?.delete()
            MutationResult.Failure("Copy failed: ${t.message}", t)
        }
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
    override suspend fun trashDestination(source: FileRef): FileRef {
        val sourceFile = File(source.requirePath())
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val sourcePath = sourceFile.absolutePath
        require(sourcePath.startsWith("$externalRoot/")) {
            "Source is outside external storage, can't compute a Trash path: $sourcePath"
        }
        val relativePath = sourcePath.removePrefix("$externalRoot/")
        return FileRef.Direct(File(File(externalRoot, TRASH_RELATIVE_ROOT), relativePath).absolutePath)
    }

    override suspend fun trash(source: FileRef): MutationResult {
        val sourceFile = File(source.requirePath())
        if (!sourceFile.exists()) return MutationResult.Failure("Source does not exist: ${sourceFile.absolutePath}")
        val destination = try {
            trashDestination(source)
        } catch (t: Throwable) {
            return MutationResult.Failure(t.message ?: "Could not resolve Trash destination", t)
        }
        return moveFile(sourceFile, File(destination.requirePath()))
    }

    override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult {
        val dir = File(ref.requirePath())
        if (!dir.exists()) return MutationResult.Success(ref, changed = false)
        if (!dir.isDirectory) return MutationResult.Failure("Undo target is not a directory: ${dir.absolutePath}")
        val children = dir.listFiles()
            ?: return MutationResult.Failure("Could not inspect directory before undo: ${dir.absolutePath}")
        if (children.isNotEmpty()) {
            return MutationResult.Failure("Directory is no longer empty; refusing to remove it: ${dir.absolutePath}")
        }
        return if (dir.delete()) {
            MutationResult.Success(ref, changed = true)
        } else {
            MutationResult.Failure("Could not remove empty directory: ${dir.absolutePath}")
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
