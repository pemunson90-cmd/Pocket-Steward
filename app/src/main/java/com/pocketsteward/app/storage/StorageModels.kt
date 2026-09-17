package com.pocketsteward.app.storage

/**
 * A reference to a single file or directory, independent of whether it was
 * reached through a direct filesystem path or a SAF content URI. Nothing
 * outside [StorageGateway] implementations should construct these from a raw
 * path/URI string.
 */
sealed interface FileRef {
    data class Direct(val absolutePath: String) : FileRef
    data class Saf(val documentUri: String) : FileRef
}

/** The path or URI a [FileRef] wraps, with no type tag — for display and as a DB column value. */
fun FileRef.rawValue(): String = when (this) {
    is FileRef.Direct -> absolutePath
    is FileRef.Saf -> documentUri
}

/**
 * The inverse of [rawValue]: reconstructs a [FileRef] from a raw string
 * pulled back out of Room (a `FileRecord.stableRef`, a plan operation's own
 * source/destination). Content URIs always start with `content://`; a
 * direct filesystem path never does, so that prefix is a safe, simple
 * discriminator between the two [FileRef] cases. Not used for
 * `MutationRecord.sourceBefore`/`destinationAfter`, which are encoded via
 * [com.pocketsteward.app.storage.FileRefJournalCodec] instead — a
 * type-tagged format chosen for the journal specifically so recovery never
 * has to guess from a bare string.
 */
fun parseFileRef(rawValue: String): FileRef =
    if (rawValue.startsWith("content://")) FileRef.Saf(rawValue) else FileRef.Direct(rawValue)

enum class StorageAccessMode { DIRECT, SAF }

/**
 * What part of storage an operation is allowed to touch. [Broad] only applies
 * when [StorageAccessMode.DIRECT] access has been granted; [Tree] wraps one
 * SAF-granted directory tree.
 */
sealed interface StorageScope {
    data object Broad : StorageScope
    data class Tree(val rootRef: FileRef.Saf, val displayName: String) : StorageScope
}

data class FileEntry(
    val ref: FileRef,
    val displayName: String,
    val isDirectory: Boolean,
    val parentRef: FileRef?,
)

data class FileMetadata(
    val ref: FileRef,
    val displayName: String,
    val extension: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val createdAtEpochMs: Long?,
    val modifiedAtEpochMs: Long?,
    val isDirectory: Boolean,
    val isHidden: Boolean,
)

sealed interface MutationResult {
    data class Success(val resultRef: FileRef, val changed: Boolean = true) : MutationResult
    data class Failure(val reason: String, val cause: Throwable? = null) : MutationResult
}
