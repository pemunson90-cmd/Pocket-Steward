package com.pocketsteward.app.storage

/**
 * Stable, type-preserving serialization for FileRefs written to durable
 * mutation journals. Raw paths alone are not enough once SAF refs exist,
 * because recovery after process death must know which gateway owns a ref.
 */
object FileRefJournalCodec {
    private const val DIRECT_PREFIX = "D:"
    private const val SAF_PREFIX = "S:"

    fun encode(ref: FileRef): String = when (ref) {
        is FileRef.Direct -> DIRECT_PREFIX + ref.absolutePath
        is FileRef.Saf -> SAF_PREFIX + ref.documentUri
    }

    fun decode(encoded: String): FileRef = when {
        encoded.startsWith(DIRECT_PREFIX) -> FileRef.Direct(encoded.removePrefix(DIRECT_PREFIX))
        encoded.startsWith(SAF_PREFIX) -> FileRef.Saf(encoded.removePrefix(SAF_PREFIX))
        else -> error("Unknown durable FileRef encoding")
    }
}
