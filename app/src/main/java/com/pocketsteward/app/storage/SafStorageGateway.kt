package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/**
 * [StorageGateway] backed by the Storage Access Framework (plan Section 5,
 * Mode B) for users who grant one directory tree instead of broad access.
 *
 * Every [FileRef.Saf] this class hands out, including the tree's own root
 * (see [rootOf]), is a *document* URI (`.../tree/X/document/Y`), never a
 * bare tree URI (`.../tree/X`). [listChildren] and [stat] both reconstruct
 * via `DocumentFile.fromTreeUri`, not `fromSingleUri`: per the library
 * source, `fromTreeUri` checks `DocumentsContract.isDocumentUri` and, when
 * true, resolves the *given* document rather than the tree's root —
 * `rootOf`'s normalization is exactly what makes that check pass for every
 * node. `fromSingleUri` was tried first and is wrong here: it returns a
 * `SingleDocumentFile`, whose `listFiles()` unconditionally throws
 * `UnsupportedOperationException`, which is why an SAF-mode scan used to
 * fail immediately. (Caught by actually building and running this — see
 * BUILD_ENVIRONMENT.md.)
 *
 * Note for Milestone 2: DocumentsContract.moveDocument only moves within the
 * same document tree/provider. A move across two separately granted trees
 * is not atomic on SAF and must be implemented as
 * copy-then-verify-then-delete-source, with its own journal semantics so a
 * crash mid-copy can't leave both a source and a partial destination behind.
 */
// In SafStorageGateway.kt and ScanTarget.GrantedFolder

/**
 * V1.1: SAF now has real read/create/write/copy/move/rename/empty-directory
 * primitives with no-overwrite behavior. The high-level organizer remains
 * conservative until preview validation can model provider-specific
 * destination URIs and Trash with the same crash-safety guarantees as Direct.
 */

class SafStorageGateway(
    private val context: Context,
) : StorageGateway {

    override suspend fun rootOf(scope: StorageScope): FileRef {
        check(scope is StorageScope.Tree) {
            "SafStorageGateway only serves StorageScope.Tree, got $scope"
        }
        val treeUri = Uri.parse(scope.rootRef.documentUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return FileRef.Saf(rootDocUri.toString())
    }

    override suspend fun listChildren(directory: FileRef): List<FileEntry> {
        val uri = Uri.parse(directory.requireUri())
        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: error("SafStorageGateway could not resolve a DocumentFile for $uri")
        return doc.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            FileEntry(
                ref = FileRef.Saf(child.uri.toString()),
                displayName = name,
                isDirectory = child.isDirectory,
                parentRef = directory,
            )
        }
    }

    override suspend fun stat(ref: FileRef): FileMetadata {
        val uri = Uri.parse(ref.requireUri())
        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: error("SafStorageGateway could not resolve a DocumentFile for $uri")
        val name = doc.name ?: uri.lastPathSegment ?: "unknown"
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return FileMetadata(
            ref = ref,
            displayName = name,
            extension = extension,
            mimeType = doc.type,
            sizeBytes = if (doc.isDirectory) 0 else doc.length(),
            // SAF's ContentResolver surface doesn't expose a creation-time
            // column consistently across providers; honest null rather than
            // guessing.
            createdAtEpochMs = null,
            modifiedAtEpochMs = doc.lastModified().takeIf { it > 0 },
            isDirectory = doc.isDirectory,
            isHidden = name.startsWith("."),
        )
    }

    override suspend fun exists(ref: FileRef): Boolean {
        val uri = Uri.parse(ref.requireUri())
        return DocumentFile.fromTreeUri(context, uri)?.exists() == true
    }

    override suspend fun openRead(ref: FileRef): InputStream {
        val uri = Uri.parse(ref.requireUri())
        return context.contentResolver.openInputStream(uri)
            ?: error("Could not open SAF document for reading: $uri")
    }
    override suspend fun createDirectory(parent: FileRef, name: String): MutationResult {
        if (!safeName(name)) return MutationResult.Failure("Unsafe folder name: $name")
        val parentDoc = runCatching { resolve(parent) }.getOrElse {
            return MutationResult.Failure(it.message ?: "Could not resolve SAF parent folder.", it)
        }
        if (!parentDoc.isDirectory) {
            return MutationResult.Failure("Parent is not a directory: ${parent.rawValue()}")
        }
        val collision = childNamed(parentDoc, name)
        if (collision != null) {
            return if (collision.isDirectory) {
                MutationResult.Success(FileRef.Saf(collision.uri.toString()), changed = false)
            } else {
                MutationResult.Failure("A file already exists with that name: $name")
            }
        }
        val created = runCatching { parentDoc.createDirectory(name) }.getOrNull()
            ?: return MutationResult.Failure("Could not create SAF directory: $name")
        return MutationResult.Success(FileRef.Saf(created.uri.toString()))
    }

    override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult {
        if (!safeName(name)) return MutationResult.Failure("Unsafe file name: $name")
        val parentDoc = runCatching { resolve(parent) }.getOrElse {
            return MutationResult.Failure(it.message ?: "Could not resolve SAF parent folder.", it)
        }
        if (!parentDoc.isDirectory) {
            return MutationResult.Failure("Parent is not a directory: ${parent.rawValue()}")
        }
        if (childNamed(parentDoc, name) != null) {
            return MutationResult.Failure("Refusing to overwrite an existing SAF document: $name")
        }
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "text/plain"
        }
        val created = runCatching { parentDoc.createFile(mime, name) }.getOrNull()
            ?: return MutationResult.Failure("Could not create SAF document: $name")
        return try {
            context.contentResolver.openOutputStream(created.uri, "wt").use { output ->
                requireNotNull(output) { "Could not open the created SAF document for writing." }
                output.writer(Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            MutationResult.Success(FileRef.Saf(created.uri.toString()))
        } catch (t: Throwable) {
            runCatching { created.delete() }
            MutationResult.Failure(t.message ?: "Could not write SAF document: $name", t)
        }
    }
    override suspend fun copy(source: FileRef, destination: FileRef): MutationResult {
        val sourceDoc = runCatching { resolve(source) }.getOrElse {
            return MutationResult.Failure(it.message ?: "Could not resolve SAF source.", it)
        }
        val destinationDir = runCatching { resolve(destination) }.getOrElse {
            return MutationResult.Failure(it.message ?: "Could not resolve SAF destination folder.", it)
        }
        if (!sourceDoc.exists() || sourceDoc.isDirectory) {
            return MutationResult.Failure("SAF copy currently supports existing files only.")
        }
        if (!destinationDir.isDirectory) {
            return MutationResult.Failure("SAF copy destination must be an existing directory.")
        }
        val name = sourceDoc.name
            ?: return MutationResult.Failure("Source document has no display name.")
        if (childNamed(destinationDir, name) != null) {
            return MutationResult.Failure("Destination already contains $name; refusing to overwrite.")
        }
        val created = runCatching {
            destinationDir.createFile(sourceDoc.type ?: "application/octet-stream", name)
        }.getOrNull() ?: return MutationResult.Failure("Could not create destination document: $name")
        return try {
            context.contentResolver.openInputStream(sourceDoc.uri).use { input ->
                requireNotNull(input) { "Could not read SAF source." }
                context.contentResolver.openOutputStream(created.uri, "w").use { output ->
                    requireNotNull(output) { "Could not write SAF destination." }
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                }
            }
            val sourceSize = sourceDoc.length()
            val destinationSize = created.length()
            if (sourceSize >= 0L && destinationSize != sourceSize) {
                runCatching { created.delete() }
                MutationResult.Failure(
                    "Copied SAF document size did not match source; partial destination was removed.",
                )
            } else {
                MutationResult.Success(FileRef.Saf(created.uri.toString()))
            }
        } catch (t: Throwable) {
            runCatching { created.delete() }
            MutationResult.Failure(t.message ?: "SAF copy failed.", t)
        }
    }

    override suspend fun move(source: FileRef, destination: FileRef): MutationResult {
        val copied = copy(source, destination)
        if (copied !is MutationResult.Success) return copied

        val sourceDoc = runCatching { resolve(source) }.getOrNull()
        if (sourceDoc == null || !sourceDoc.delete()) {
            return MutationResult.Failure(
                "Copied the file to the SAF destination, but could not remove the original. Both copies were left in place.",
            )
        }
        return copied
    }

    override suspend fun rename(source: FileRef, newName: String): MutationResult {
        if (!safeName(newName)) return MutationResult.Failure("Unsafe file name: $newName")
        val sourceDoc = runCatching { resolve(source) }.getOrElse {
            return MutationResult.Failure(it.message ?: "Could not resolve SAF source.", it)
        }
        if (!sourceDoc.exists()) return MutationResult.Failure("Source no longer exists.")
        return try {
            if (!sourceDoc.renameTo(newName)) {
                MutationResult.Failure("SAF provider refused to rename the document.")
            } else {
                MutationResult.Success(FileRef.Saf(sourceDoc.uri.toString()))
            }
        } catch (t: Throwable) {
            MutationResult.Failure(t.message ?: "SAF rename failed.", t)
        }
    }

    override suspend fun trashDestination(source: FileRef): FileRef =
        throw UnsupportedOperationException(
            "Selected-folder access is read-only for mutations; Trash requires full file-manager access.",
        )

    override suspend fun trash(source: FileRef): MutationResult =
        unsupportedMutation("move files to Trash")

    override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult {
        val doc = runCatching { resolve(ref) }.getOrElse {
            return MutationResult.Failure(it.message ?: "Could not resolve SAF directory.", it)
        }
        if (!doc.exists()) return MutationResult.Success(ref, changed = false)
        if (!doc.isDirectory) return MutationResult.Failure("Undo target is not a directory.")
        if (doc.listFiles().isNotEmpty()) {
            return MutationResult.Failure("Directory is no longer empty; refusing to remove it.")
        }
        return if (doc.delete()) {
            MutationResult.Success(ref)
        } else {
            MutationResult.Failure("SAF provider refused to remove the empty directory.")
        }
    }

    private fun resolve(ref: FileRef): DocumentFile {
        val uri = Uri.parse(ref.requireUri())
        return DocumentFile.fromTreeUri(context, uri)
            ?: error("SafStorageGateway could not resolve a DocumentFile for $uri")
    }

    private fun childNamed(parent: DocumentFile, name: String): DocumentFile? =
        parent.listFiles().firstOrNull {
            it.name?.equals(name, ignoreCase = true) == true
        }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name

}

private fun FileRef.requireUri(): String =
    (this as? FileRef.Saf)?.documentUri
        ?: error("SafStorageGateway received a non-Saf FileRef: $this")
