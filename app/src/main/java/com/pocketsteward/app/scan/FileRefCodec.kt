package com.pocketsteward.app.scan

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec

/**
 * Serializes a pending-directory queue for [ScanCheckpoint][com.pocketsteward.app.data.db.ScanCheckpoint].
 * Deliberately plain text, not JSON — a `FileRef` is one tag plus one
 * string with no nested structure, and paths/URIs can't contain a literal
 * newline, so a newline-joined "tag:value" list round-trips exactly and
 * needs no library.
 */
object FileRefCodec {
    private const val DIRECT_TAG = "D"
    private const val SAF_TAG = "S"
    private const val CHILD_TAG = "C"

    fun encodeList(refs: List<FileRef>): String = refs.joinToString("\n") { encode(it) }

    fun decodeList(encoded: String): List<FileRef> =
        if (encoded.isBlank()) emptyList() else encoded.split("\n").map { decode(it) }

    private fun encode(ref: FileRef): String = when (ref) {
        is FileRef.Direct -> "$DIRECT_TAG:${ref.absolutePath}"
        is FileRef.Saf -> "$SAF_TAG:${ref.documentUri}"
        is FileRef.Child -> "$CHILD_TAG:${FileRefJournalCodec.encode(ref)}"
    }

    private fun decode(line: String): FileRef {
        val tag = line.substringBefore(':')
        val value = line.substringAfter(':')
        return when (tag) {
            DIRECT_TAG -> FileRef.Direct(value)
            SAF_TAG -> FileRef.Saf(value)
            CHILD_TAG -> FileRefJournalCodec.decode(value)
            else -> error("Unknown FileRef tag in checkpoint: $tag")
        }
    }
}
