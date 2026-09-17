package com.pocketsteward.app.storage

import android.content.Context
import java.io.InputStream

/**
 * [StorageGateway] backed by the Storage Access Framework (plan Section 5,
 * Mode B) for users who grant one directory tree instead of broad access.
 *
 * Note for Milestone 1 implementation: DocumentsContract.moveDocument only
 * moves within the same document tree/provider. A move across two separately
 * granted trees is not atomic on SAF and must be implemented as
 * copy-then-verify-then-delete-source, with its own journal semantics so a
 * crash mid-copy can't leave both a source and a partial destination behind.
 */
class SafStorageGateway(
    @Suppress("unused") private val context: Context,
) : StorageGateway {

    override suspend fun list(scope: StorageScope): List<FileEntry> =
        TODO("Milestone 1: recursive scanner via DocumentFile/DocumentsContract")

    override suspend fun stat(ref: FileRef): FileMetadata =
        TODO("Milestone 1")

    override suspend fun openRead(ref: FileRef): InputStream =
        TODO("Milestone 1")

    override suspend fun createDirectory(parent: FileRef, name: String): FileRef =
        TODO("Milestone 2")

    override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
        TODO("Milestone 2: same-tree moveDocument vs. cross-tree copy+delete")

    override suspend fun rename(source: FileRef, newName: String): MutationResult =
        TODO("Milestone 2")

    override suspend fun trash(source: FileRef): MutationResult =
        TODO("Milestone 2/14")
}
