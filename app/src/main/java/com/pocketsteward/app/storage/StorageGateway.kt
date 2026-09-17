package com.pocketsteward.app.storage

import java.io.InputStream

/**
 * The only path from the rest of the app to the real filesystem. The executor
 * (Milestone 2+) talks exclusively to this interface so that neither the agent
 * nor the UI can perform an unvalidated mutation directly (see plan Section 5/11).
 *
 * Implementations: [DirectStorageGateway] (MANAGE_EXTERNAL_STORAGE) and
 * [SafStorageGateway] (Storage Access Framework). Real bodies land in
 * Milestone 1; Milestone 0 only needs this contract and the access-mode
 * detection used by onboarding.
 */
interface StorageGateway {
    suspend fun list(scope: StorageScope): List<FileEntry>
    suspend fun stat(ref: FileRef): FileMetadata
    suspend fun openRead(ref: FileRef): InputStream
    suspend fun createDirectory(parent: FileRef, name: String): FileRef
    suspend fun move(source: FileRef, destination: FileRef): MutationResult
    suspend fun rename(source: FileRef, newName: String): MutationResult
    suspend fun trash(source: FileRef): MutationResult
}
