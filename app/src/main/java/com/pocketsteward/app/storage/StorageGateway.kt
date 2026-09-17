package com.pocketsteward.app.storage

import java.io.InputStream

/**
 * The only path from the rest of the app to the real filesystem. The executor
 * talks exclusively to this interface so neither the agent nor the UI can
 * perform an unvalidated mutation directly.
 */
interface StorageGateway {
    suspend fun rootOf(scope: StorageScope): FileRef
    suspend fun listChildren(directory: FileRef): List<FileEntry>
    suspend fun list(scope: StorageScope): List<FileEntry> = listChildren(rootOf(scope))
    suspend fun stat(ref: FileRef): FileMetadata
    suspend fun exists(ref: FileRef): Boolean
    suspend fun openRead(ref: FileRef): InputStream

    /**
     * Returns Success(changed=false) when the directory already existed.
     * That distinction is load-bearing for undo: Pocket Steward must never
     * remove a directory it did not create merely because a plan contained a
     * harmless create-if-missing step.
     */
    suspend fun createDirectory(parent: FileRef, name: String): MutationResult

    suspend fun move(source: FileRef, destination: FileRef): MutationResult
    suspend fun rename(source: FileRef, newName: String): MutationResult

    /** The deterministic destination [trash] will use, for write-ahead journaling/recovery. */
    suspend fun trashDestination(source: FileRef): FileRef
    suspend fun trash(source: FileRef): MutationResult

    /**
     * Removes only an empty directory. This exists solely for undoing a
     * directory Pocket Steward itself created; it is not a general delete API.
     */
    suspend fun removeEmptyDirectory(ref: FileRef): MutationResult
}
