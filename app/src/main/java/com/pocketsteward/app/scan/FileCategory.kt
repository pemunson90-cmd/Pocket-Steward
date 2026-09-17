package com.pocketsteward.app.scan

/**
 * The coarse extension-based buckets the Scan Summary screen groups by
 * (plan Section 16's example: Images / Documents / APKs / Archives /
 * Audio-Video / Other). This is deliberately not the Milestone 4 rule
 * engine — no confidence, no reasons, no user-configurable project terms,
 * just an extension lookup, because that's all a Level 0 inventory needs.
 */
enum class FileCategory {
    IMAGE,
    DOCUMENT,
    APK,
    ARCHIVE,
    AUDIO_VIDEO,
    OTHER,
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
private val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "txt", "md", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv",
)
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val AUDIO_VIDEO_EXTENSIONS = setOf(
    "mp3", "wav", "flac", "aac", "ogg", "m4a",
    "mp4", "mkv", "webm", "avi", "mov", "3gp",
)

fun classifyByExtension(extension: String): FileCategory {
    val ext = extension.lowercase()
    return when {
        ext == "apk" -> FileCategory.APK
        ext in IMAGE_EXTENSIONS -> FileCategory.IMAGE
        ext in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENT
        ext in ARCHIVE_EXTENSIONS -> FileCategory.ARCHIVE
        ext in AUDIO_VIDEO_EXTENSIONS -> FileCategory.AUDIO_VIDEO
        else -> FileCategory.OTHER
    }
}
