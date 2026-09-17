package com.pocketsteward.app.storage

import java.io.InputStream

/**
 * The only path from the rest of the app to the real filesystem. The executor
 * (Milestone 2+) talks exclusively to this interface so that neither the agent
 * nor the UI can perform an unvalidated mutation directly (see plan Section 5/11).
 *
 * Implementations: [DirectStorageGateway] (MANAGE_EXTERNAL_STORAGE) and
 * [SafStorageGateway] (Storage Access Framework). Read methods ([rootOf],
 * [listChildren], [stat]) are real as of Milestone 1; the mutation methods
 * stay `TODO()` until Milestone 2's validator/executor exist to gate them.
 */
interface StorageGateway {
    /** The starting [FileRef] a scope resolves to — what a scanner walks from. */
    suspend fun rootOf(scope: StorageScope): FileRef

    /**
     * Immediate children of [directory]. Not recursive — [FileScanner]
     * (Milestone 1) does the walk by calling this repeatedly, so gateway
     * implementations stay simple single-level listings.
     */
    suspend fun listChildren(directory: FileRef): List<FileEntry>

    /** Convenience for "list what's directly under this scope's root." */
    suspend fun list(scope: StorageScope): List<FileEntry> = listChildren(rootOf(scope))

    suspend fun stat(ref: FileRef): FileMetadata
    suspend fun openRead(ref: FileRef): InputStream
    suspend fun createDirectory(parent: FileRef, name: String): FileRef
    suspend fun move(source: FileRef, destination: FileRef): MutationResult
    suspend fun rename(source: FileRef, newName: String): MutationResult
    suspend fun trash(source: FileRef): MutationResult

    /**
     * Undo's inverse of [createDirectory] (plan Section 15's own example:
     * "CREATE DIRECTORY X / undo = REMOVE X only if empty and created by
     * this task"). Removes [ref] only if it's a directory and currently has
     * no children; if anything has been put into it since, this is a no-op
     * failure, not a forced removal — undoing a folder creation must never
     * take other data down with it. This is the one place in the app that
     * removes anything from disk outright rather than trashing it, and it's
     * narrow by design: an empty directory that this exact task created has
     * nothing in it to lose.
     */
    suspend fun removeIfEmpty(ref: FileRef): MutationResult
}
