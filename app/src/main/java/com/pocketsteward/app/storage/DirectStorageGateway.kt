package com.pocketsteward.app.storage

import android.content.Context
import java.io.InputStream

/**
 * [StorageGateway] backed by direct java.io.File access under
 * MANAGE_EXTERNAL_STORAGE (plan Section 5, Mode A). Milestone 0 only wires
 * this into the DI container; the scanning/mutation bodies are Milestone 1/2
 * work and are intentionally unimplemented until then so a half-correct
 * implementation doesn't get exercised on real files.
 */
class DirectStorageGateway(
    @Suppress("unused") private val context: Context,
) : StorageGateway {

    override suspend fun list(scope: StorageScope): List<FileEntry> =
        TODO("Milestone 1: recursive scanner")

    override suspend fun stat(ref: FileRef): FileMetadata =
        TODO("Milestone 1: Level 0 metadata extraction")

    override suspend fun openRead(ref: FileRef): InputStream =
        TODO("Milestone 1")

    override suspend fun createDirectory(parent: FileRef, name: String): FileRef =
        TODO("Milestone 2: deterministic executor")

    override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
        TODO("Milestone 2: deterministic executor")

    override suspend fun rename(source: FileRef, newName: String): MutationResult =
        TODO("Milestone 2: deterministic executor")

    override suspend fun trash(source: FileRef): MutationResult =
        TODO("Milestone 2/14: quarantine-based trash, never permanent delete")
}
