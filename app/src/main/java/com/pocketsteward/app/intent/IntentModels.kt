package com.pocketsteward.app.intent

import com.pocketsteward.app.scan.FileCategory

/** The deliberately small command vocabulary M9 accepts without a model. */
enum class IntentAction {
    ORGANIZE,
    FIND,
    GROUP,
    MOVE,
    COPY,
    RENAME,
    ARCHIVE,
    DUPLICATE_REVIEW,
}

enum class GroupingMode {
    TYPE,
    PROJECT,
}

enum class IntentOrder {
    DEFAULT,
    LARGEST_FIRST,
    SMALLEST_FIRST,
    NEWEST_FIRST,
    OLDEST_FIRST,
}

/**
 * Parsed natural-language intent. This type contains no filesystem mutation
 * primitive; it is only input to deterministic planners/read-only queries.
 */
data class BoundedIntent(
    val action: IntentAction,
    val rawRequest: String,
    val categories: Set<FileCategory> = emptySet(),
    val groupingMode: GroupingMode = GroupingMode.TYPE,
    val mainFolder: String? = null,
    val includeSubfolders: Boolean = false,
    val leaveUncertain: Boolean = true,
    /** Filename term for ordinary metadata-only find requests. */
    val findTerm: String? = null,
    /** Content term means the request explicitly asked to inspect file contents. */
    val contentTerm: String? = null,
    val renameFrom: String? = null,
    val renameTo: String? = null,
    /** Batch rename: filename substring to match and a deterministic template. */
    val renameMatchTerm: String? = null,
    val renameTemplate: String? = null,
    /** Explicit destination folder for bounded move/copy requests. */
    val destinationFolder: String? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val modifiedBefore: Long? = null,
    val modifiedAfter: Long? = null,
    val order: IntentOrder = IntentOrder.DEFAULT,
    val resultLimit: Int? = null,
)

sealed interface IntentParseResult {
    data class Parsed(val intent: BoundedIntent) : IntentParseResult
    data class Unsupported(val reason: String) : IntentParseResult
}
