package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.security.MessageDigest

/**
 * Storage Access Framework backend.
 *
 * Concrete provider URIs are used for existing documents. [FileRef.Child]
 * names a prospective child before the provider has assigned it a URI, which
 * lets the validator and write-ahead journal describe SAF mutations before
 * they happen instead of inventing a provider-specific URI.
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
        val doc = resolve(directory)
        check(doc.isDirectory) { "SAF reference is not a directory: ${directory.rawValue()}" }
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
        val doc = resolve(ref)
        val name = doc.name ?: "unknown"
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return FileMetadata(
            ref = FileRef.Saf(doc.uri.toString()),
            displayName = name,
            extension = extension,
            mimeType = doc.type,
            sizeBytes = if (doc.isDirectory) 0 else doc.length(),
            createdAtEpochMs = null,
            modifiedAtEpochMs = doc.lastModified().takeIf { it > 0 },
            isDirectory = doc.isDirectory,
            isHidden = name.startsWith("."),
        )
    }

    override suspend fun exists(ref: FileRef): Boolean =
        runCatching { resolve(ref).exists() }.getOrDefault(false)

    override suspend fun openRead(ref: FileRef): InputStream {
        val doc = resolve(ref)
        check(!doc.isDirectory) { "Cannot open a SAF directory for reading." }
        return context.contentResolver.openInputStream(doc.uri)
            ?: error("Could not open SAF document for reading: ${doc.uri}")
    }

    override suspend fun createDirectory(parent: FileRef, name: String): MutationResult {
        if (!safeName(name)) return MutationResult.Failure("Unsafe folder name: $name")
        val parentDoc = resolveOrFailure(parent, "parent folder") ?: return MutationResult.Failure(
            "Could not resolve SAF parent folder.",
        )
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

    override suspend fun writeTextFile(
        parent: FileRef,
        name: String,
        content: String,
    ): MutationResult {
        if (!safeName(name)) return MutationResult.Failure("Unsafe file name: $name")
        val parentDoc = resolveOrFailure(parent, "parent folder")
            ?: return MutationResult.Failure("Could not resolve SAF parent folder.")
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

    /**
     * [destination] is the final file reference, matching DirectStorageGateway.
     * A symbolic child is the normal SAF form. For backwards compatibility a
     * concrete directory is also accepted and uses the source display name.
     */
    override suspend fun copy(source: FileRef, destination: FileRef): MutationResult {
        val sourceDoc = resolveOrFailure(source, "source")
            ?: return MutationResult.Failure("Could not resolve SAF source.")
        if (!sourceDoc.exists() || sourceDoc.isDirectory) {
            return MutationResult.Failure("SAF copy currently supports existing files only.")
        }

        val target = destinationTarget(destination, sourceDoc.name)
            ?: return MutationResult.Failure(
                "SAF copy destination must be a prospective child or an existing directory.",
            )
        val (destinationDir, name) = target
        if (!destinationDir.isDirectory) {
            return MutationResult.Failure("SAF copy destination parent is not a directory.")
        }
        if (!safeName(name)) return MutationResult.Failure("Unsafe destination name: $name")
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

        val sourceDoc = resolveOrFailure(source, "source")
        if (sourceDoc == null || !sourceDoc.delete()) {
            // A failed move must not leave an untracked extra copy if the
            // provider lets us clean it up.
            runCatching { resolve(copied.resultRef).delete() }
            return MutationResult.Failure(
                "Copied the file to the SAF destination, but could not remove the original. " +
                    "The new copy was removed when possible.",
            )
        }
        return copied
    }

    override suspend fun rename(source: FileRef, newName: String): MutationResult {
        if (!safeName(newName)) return MutationResult.Failure("Unsafe file name: $newName")
        val sourceDoc = resolveOrFailure(source, "source")
            ?: return MutationResult.Failure("Could not resolve SAF source.")
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

    /**
     * Selected-folder Trash is app-managed inside the granted tree. The
     * filename is deterministic and collision-resistant, while the journal
     * retains the original parent/name for exact undo.
     */
    override suspend fun trashDestination(source: FileRef): FileRef {
        val sourceDoc = resolve(source)
        val name = sourceDoc.name ?: error("Source document has no display name.")
        val root = treeRootFor(source)
        val steward = FileRef.Child(root, "PocketSteward")
        val trash = FileRef.Child(steward, "Trash")
        val suffix = sha256(source.rawValue()).take(12)
        return FileRef.Child(trash, "$suffix-$name")
    }

    override suspend fun trash(source: FileRef): MutationResult {
        val destination = runCatching { trashDestination(source) }.getOrElse {
            return MutationResult.Failure(it.message ?: "Could not resolve SAF Trash destination.", it)
        }
        val trash = (destination as FileRef.Child).parent
        val steward = (trash as FileRef.Child).parent

        when (val first = createDirectory((steward as FileRef.Child).parent, steward.name)) {
            is MutationResult.Failure -> return first
            is MutationResult.Success -> Unit
        }
        when (val second = createDirectory(steward, (trash as FileRef.Child).name)) {
            is MutationResult.Failure -> return second
            is MutationResult.Success -> Unit
        }
        return move(source, destination)
    }

    override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult {
        val doc = resolveOrFailure(ref, "directory")
            ?: return MutationResult.Failure("Could not resolve SAF directory.")
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

    private fun resolve(ref: FileRef): DocumentFile = when (ref) {
        is FileRef.Saf -> {
            val uri = Uri.parse(ref.documentUri)
            DocumentFile.fromTreeUri(context, uri)
                ?: error("SafStorageGateway could not resolve a DocumentFile for $uri")
        }
        is FileRef.Child -> {
            val parent = resolve(ref.parent)
            childNamed(parent, ref.name)
                ?: error("SAF child does not exist yet: ${ref.name}")
        }
        is FileRef.Direct -> error("SafStorageGateway received a Direct FileRef: $ref")
    }

    private fun resolveOrFailure(ref: FileRef, label: String): DocumentFile? =
        runCatching { resolve(ref) }.getOrNull()

    private fun destinationTarget(
        destination: FileRef,
        fallbackName: String?,
    ): Pair<DocumentFile, String>? = when (destination) {
        is FileRef.Child -> {
            val parent = runCatching { resolve(destination.parent) }.getOrNull() ?: return null
            parent to destination.name
        }
        is FileRef.Saf -> {
            val doc = runCatching { resolve(destination) }.getOrNull() ?: return null
            if (!doc.isDirectory) return null
            val name = fallbackName ?: return null
            doc to name
        }
        is FileRef.Direct -> null
    }

    private fun treeRootFor(ref: FileRef): FileRef.Saf {
        val concrete = when (ref) {
            is FileRef.Saf -> ref
            is FileRef.Child -> return treeRootFor(ref.parent)
            is FileRef.Direct -> error("Direct reference has no SAF tree root.")
        }
        val uri = Uri.parse(concrete.documentUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri),
        )
        return FileRef.Saf(root.toString())
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
            '\\' !in name &&
            name.none { it.isISOControl() }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
