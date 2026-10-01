package com.pocketsteward.app.share

import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef

/** Ordinary share providers use OpenableColumns, not DocumentsProvider-only columns. */
object SharedContentMetadata {
    fun build(ref: FileRef.Saf, name: String?, declaredSize: Long?, descriptorSize: Long?, mimeType: String?, fallbackName: String): FileMetadata {
        val displayName = name?.takeIf { it.isNotBlank() } ?: fallbackName
        val size = declaredSize?.takeIf { it >= 0 } ?: descriptorSize?.takeIf { it >= 0 } ?: -1
        return FileMetadata(ref, displayName, displayName.substringAfterLast('.', "").lowercase(), mimeType, size, null, null,
            mimeType == "vnd.android.document/directory", displayName.startsWith('.'))
    }
}
