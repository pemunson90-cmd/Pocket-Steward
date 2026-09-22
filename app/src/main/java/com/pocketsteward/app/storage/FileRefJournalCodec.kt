package com.pocketsteward.app.storage

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Stable, type-preserving serialization for FileRefs written to durable
 * mutation journals. Raw paths alone are not enough once SAF refs exist,
 * because recovery after process death must know which gateway owns a ref.
 */
object FileRefJournalCodec {
    private const val DIRECT_PREFIX = "D:"
    private const val SAF_PREFIX = "S:"
    private const val CHILD_PREFIX = "C:"

    fun encode(ref: FileRef): String = when (ref) {
        is FileRef.Direct -> DIRECT_PREFIX + ref.absolutePath
        is FileRef.Saf -> SAF_PREFIX + ref.documentUri
        is FileRef.Child -> {
            val parent = b64(encode(ref.parent))
            val name = b64(ref.name)
            "$CHILD_PREFIX$parent:$name"
        }
    }

    fun decode(encoded: String): FileRef = when {
        encoded.startsWith(DIRECT_PREFIX) -> FileRef.Direct(encoded.removePrefix(DIRECT_PREFIX))
        encoded.startsWith(SAF_PREFIX) -> FileRef.Saf(encoded.removePrefix(SAF_PREFIX))
        encoded.startsWith(CHILD_PREFIX) -> {
            val payload = encoded.removePrefix(CHILD_PREFIX)
            val separator = payload.indexOf(':')
            require(separator > 0 && separator < payload.lastIndex) {
                "Malformed durable child FileRef."
            }
            val parent = decode(unb64(payload.substring(0, separator)))
            val name = unb64(payload.substring(separator + 1))
            FileRef.Child(parent, name)
        }
        else -> error("Unknown durable FileRef encoding")
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
