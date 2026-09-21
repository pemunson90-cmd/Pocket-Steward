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
 * 2026-09-19 (M8 Spec): SAF mode remains a browser/read-only fallback.
 * Mutation methods fail closed with an ordinary MutationResult.Failure rather
 * than TODO/NotImplementedError, so an accidental UI-fence regression cannot
 * crash the app or create a second mutation path.
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
    override suspend fun createDirectory(parent: FileRef, name: String): MutationResult =
        unsupportedMutation("create folders")

    override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult =
        unsupportedMutation("write files")
    override suspend fun copy(source: FileRef, destination: FileRef): MutationResult =
        MutationResult.Failure(
            "SAF copy needs destination-parent semantics that this plan operation does not yet encode.",
        )

    override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
        unsupportedMutation("move files")

    override suspend fun rename(source: FileRef, newName: String): MutationResult =
        unsupportedMutation("rename files")

    override suspend fun trashDestination(source: FileRef): FileRef =
        throw UnsupportedOperationException(
            "Selected-folder access is read-only for mutations; Trash requires full file-manager access.",
        )

    override suspend fun trash(source: FileRef): MutationResult =
        unsupportedMutation("move files to Trash")

    override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult =
        unsupportedMutation("remove directories")

    private fun unsupportedMutation(action: String): MutationResult =
        MutationResult.Failure(
            "Selected-folder access is read-only for mutations and cannot $action. " +
                "Use full file-manager access for organization tasks.",
        )

}

private fun FileRef.requireUri(): String =
    (this as? FileRef.Saf)?.documentUri
        ?: error("SafStorageGateway received a non-Saf FileRef: $this")
