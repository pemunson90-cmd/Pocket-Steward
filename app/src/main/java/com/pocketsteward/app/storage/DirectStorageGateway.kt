package com.pocketsteward.app.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

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

    /**
     * Free space that can actually be used for a write into [dir].
     *
     * `File.usableSpace` ignores cached data Android will clear on demand, so on
     * a nearly full phone it under-reports and a move or copy is refused while
     * there is room. `StorageManager.getAllocatableBytes` counts that clearable
     * cache. The larger of the two is used, so this can only ever allow what
     * the old check allowed or more, never less; any failure falls back to the
     * old number.
     */
    private fun availableBytes(dir: File): Long {
        val usable = dir.usableSpace
        val allocatable = runCatching {
            val storage = context.getSystemService(StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        }.getOrNull() ?: return usable
        return maxOf(usable, allocatable)
    }

    override suspend fun rootOf(scope: StorageScope): FileRef {
        check(scope is StorageScope.Broad) {
            "DirectStorageGateway only serves StorageScope.Broad, got $scope"
        }
        return FileRef.Direct(Environment.getExternalStorageDirectory().absolutePath)
    }

    override suspend fun listChildren(directory: FileRef): List<FileEntry> {
        val dir = File(directory.requirePath())
        val children = dir.listFiles() ?: error("Could not inspect directory; access may have changed")
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
        protectionFailureDestination(dir)?.let { return it }
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
        protectionFailureDestination(target)?.let { return it }
        val requiredBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
        if (!StorageCapacityPolicy.canFit(requiredBytes, availableBytes(parentDir))) {
            return MutationResult.Failure(
                StorageCapacityPolicy.failureMessage(requiredBytes, availableBytes(parentDir)),
            )
        }
        return try {
            target.writeText(content)
            MutationResult.Success(FileRef.Direct(target.absolutePath), changed = true)
        } catch (t: Throwable) {
            val removed = !target.exists() || target.delete()
            MutationResult.Failure(
                buildString {
                    append(t.message ?: "Failed to write ${target.absolutePath}")
                    if (!removed) {
                        append(". A partial file remains and needs review: ${target.absolutePath}")
                    }
                },
                t,
            )
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
        if (!parent.isDirectory) {
            return MutationResult.Failure(
                "Destination parent does not exist or is not a directory: ${parent.absolutePath}. " +
                    "Pocket Steward will not create an unjournaled parent implicitly.",
            )
        }
        protectionFailureDestination(destinationFile)?.let { return it }
        val requiredBytes = sourceFile.length()
        if (!StorageCapacityPolicy.canFit(requiredBytes, availableBytes(parent))) {
            return MutationResult.Failure(
                StorageCapacityPolicy.failureMessage(requiredBytes, availableBytes(parent)),
            )
        }
        return try {
            sourceFile.copyTo(destinationFile, overwrite = false)
            val verified = destinationFile.length() == sourceFile.length() &&
                sha256(sourceFile).contentEquals(sha256(destinationFile))
            if (!verified) {
                destinationFile.delete()
                MutationResult.Failure(
                    "Copied file did not verify against the source; the destination was removed.",
                )
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
        val destinationFile = File(destination.requirePath())
        // The mirrored folders under PocketSteward/Trash are app-managed, the
        // same as granted-folder mode's Trash, so they are created here rather
        // than planned. Without this, trashing a file from any folder whose
        // mirror didn't exist yet failed. Nothing outside the Trash root is
        // ever created by this path.
        val trashRoot = File(Environment.getExternalStorageDirectory(), TRASH_RELATIVE_ROOT).absolutePath
        val parent = destinationFile.parentFile
        if (parent != null && !parent.isDirectory &&
            (parent.absolutePath == trashRoot || parent.absolutePath.startsWith("$trashRoot/"))
        ) {
            parent.mkdirs()
        }
        return moveFile(sourceFile, destinationFile)
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
        protectionFailure(dir)?.let { return it }
        return if (dir.delete()) {
            MutationResult.Success(ref, changed = true)
        } else {
            MutationResult.Failure("Could not remove empty directory: ${dir.absolutePath}")
        }
    }

    private fun protectionFailure(sourceFile: File): MutationResult.Failure? =
        DirectProtection.refusal(Environment.getExternalStorageDirectory().absolutePath, sourceFile.absolutePath)
            ?.let { MutationResult.Failure(it) }

    private fun protectionFailureDestination(destinationFile: File): MutationResult.Failure? =
        DirectProtection.refusalDestination(Environment.getExternalStorageDirectory().absolutePath, destinationFile.absolutePath)
            ?.let { MutationResult.Failure(it) }

    private fun moveFile(sourceFile: File, destinationFile: File): MutationResult {
        protectionFailure(sourceFile)?.let { return it }
        if (!sourceFile.exists()) return MutationResult.Failure("Source does not exist: ${sourceFile.absolutePath}")
        if (destinationFile.exists()) return MutationResult.Failure("Destination already exists: ${destinationFile.absolutePath}")

        val destinationParent = destinationFile.parentFile
            ?: return MutationResult.Failure("Cannot determine destination parent: ${destinationFile.absolutePath}")
        if (!destinationParent.isDirectory) {
            return MutationResult.Failure(
                "Destination parent does not exist or is not a directory: ${destinationParent.absolutePath}. " +
                    "Pocket Steward will not create an unjournaled parent implicitly.",
            )
        }
        protectionFailureDestination(destinationFile)?.let { return it }

        if (sourceFile.renameTo(destinationFile)) {
            return MutationResult.Success(FileRef.Direct(destinationFile.absolutePath))
        }

        if (sourceFile.isDirectory) {
            return MutationResult.Failure(
                "Directory move could not be completed atomically. " +
                    "Pocket Steward will not fall back to an unjournaled recursive copy.",
            )
        }

        val requiredBytes = sourceFile.length()
        val usableBytes = availableBytes(destinationParent)
        if (!StorageCapacityPolicy.canFit(requiredBytes, usableBytes)) {
            return MutationResult.Failure(
                StorageCapacityPolicy.failureMessage(requiredBytes, usableBytes),
            )
        }

        // renameTo fails across filesystem boundaries (e.g. internal storage
        // to an SD card) even when both are under MANAGE_EXTERNAL_STORAGE.
        // Fall back to copy-verify-delete. The source is not removed until the
        // destination has the same byte length and SHA-256.
        return try {
            sourceFile.copyTo(destinationFile, overwrite = false)

            val sizeMatches = sourceFile.length() == destinationFile.length()
            val hashMatches = sizeMatches &&
                sha256(sourceFile).contentEquals(sha256(destinationFile))
            if (!hashMatches) {
                destinationFile.delete()
                return MutationResult.Failure(
                    "Cross-volume copy could not be verified; the partial destination was removed and the source was kept.",
                )
            }

            // A marker can appear while cross-volume bytes are being copied. A destination
            // that became protected is left intact with the source for explicit review; cleanup
            // would itself be an unauthorized mutation inside the newly protected folder.
            protectionFailure(destinationFile)?.let { blocked ->
                return MutationResult.Failure(
                    blocked.reason + " The verified destination copy remains; both files need review.",
                )
            }
            protectionFailure(sourceFile)?.let { blocked ->
                val removed = destinationFile.delete()
                return if (removed) blocked else MutationResult.Failure(
                    blocked.reason + " The verified destination copy remains; both files need review.")
            }
            if (!sourceFile.delete()) {
                // Restore the pre-move shape if the provider/filesystem lets
                // us. A failed move should not silently manufacture a second
                // durable copy that the journal considers FAILED.
                val rolledBack = destinationFile.delete()
                return MutationResult.Failure(
                    if (rolledBack) {
                        "Copied and verified the destination but could not remove the original; the destination copy was removed and the source was kept."
                    } else {
                        "Copied and verified the destination but could not remove the original, and the destination copy could not be removed. Both paths now exist and need review."
                    },
                )
            }
            MutationResult.Success(FileRef.Direct(destinationFile.absolutePath))
        } catch (t: Throwable) {
            val removed = !destinationFile.exists() || destinationFile.delete()
            MutationResult.Failure(
                buildString {
                    append("Copy+verify+delete fallback failed: ${t.message}")
                    if (!removed) {
                        append(". A destination copy remains and needs review: ${destinationFile.absolutePath}")
                    }
                },
                t,
            )
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val TRASH_RELATIVE_ROOT = "PocketSteward/Trash"
    }
}

private fun FileRef.requirePath(): String = when (this) {
    is FileRef.Direct -> absolutePath
    is FileRef.Child -> File(parent.requirePath(), name).absolutePath
    is FileRef.Saf -> error("DirectStorageGateway received a SAF FileRef: $this")
}
