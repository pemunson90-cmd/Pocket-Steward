package com.pocketsteward.app.ui.components

/**
 * What a file is, for the purpose of drawing it. Decided from the name and
 * MIME type alone, so it costs nothing, works for files that no longer exist
 * (Trash, history), and never touches the file's bytes.
 */
enum class FileKind(val canPreview: Boolean) {
    IMAGE(canPreview = true),
    VIDEO(canPreview = true),
    PDF(canPreview = true),
    AUDIO(canPreview = false),
    DOCUMENT(canPreview = false),
    SPREADSHEET(canPreview = false),
    SLIDES(canPreview = false),
    TEXT(canPreview = false),
    CODE(canPreview = false),
    ARCHIVE(canPreview = false),
    APP(canPreview = false),
    EBOOK(canPreview = false),
    FOLDER(canPreview = false),
    OTHER(canPreview = false),
    ;

    /** Spoken name for screen readers, so a badge is never announced as a bare extension. */
    val spokenName: String
        get() = when (this) {
            IMAGE -> "Image"
            VIDEO -> "Video"
            PDF -> "PDF"
            AUDIO -> "Audio"
            DOCUMENT -> "Document"
            SPREADSHEET -> "Spreadsheet"
            SLIDES -> "Presentation"
            TEXT -> "Text file"
            CODE -> "Code file"
            ARCHIVE -> "Archive"
            APP -> "App installer"
            EBOOK -> "E-book"
            FOLDER -> "Folder"
            OTHER -> "File"
        }

    companion object {
        private val byExtension: Map<String, FileKind> = buildMap {
            listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "dng", "tif", "tiff")
                .forEach { put(it, IMAGE) }
            listOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v", "ts")
                .forEach { put(it, VIDEO) }
            put("pdf", PDF)
            listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "mid", "midi")
                .forEach { put(it, AUDIO) }
            listOf("doc", "docx", "odt", "rtf", "pages")
                .forEach { put(it, DOCUMENT) }
            listOf("xls", "xlsx", "ods", "csv", "tsv", "numbers")
                .forEach { put(it, SPREADSHEET) }
            listOf("ppt", "pptx", "odp", "key")
                .forEach { put(it, SLIDES) }
            listOf("txt", "md", "markdown", "log", "srt", "vtt", "ini", "cfg", "conf")
                .forEach { put(it, TEXT) }
            listOf(
                "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "html", "htm", "css",
                "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "ps1", "sql", "yaml",
                "yml", "toml", "gradle", "swift", "lua",
            ).forEach { put(it, CODE) }
            listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst")
                .forEach { put(it, ARCHIVE) }
            listOf("apk", "apks", "xapk", "apkm", "aab")
                .forEach { put(it, APP) }
            listOf("epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr")
                .forEach { put(it, EBOOK) }
        }

        /** Every extension that maps to [kind], for category queries. */
        fun extensionsOf(vararg kinds: FileKind): List<String> =
            byExtension.filterValues { it in kinds }.keys.sorted()

        /**
         * The extension wins over the MIME type: MediaStore and SAF providers
         * report `application/octet-stream` for a large share of real files,
         * while the name is what the user sees and trusts.
         */
        fun of(name: String, mimeType: String? = null, isDirectory: Boolean = false): FileKind {
            if (isDirectory) return FOLDER
            byExtension[extensionOf(name)]?.let { return it }
            val mime = mimeType?.lowercase().orEmpty()
            return when {
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime == "application/pdf" -> PDF
                mime.startsWith("text/") -> TEXT
                mime == "application/vnd.android.package-archive" -> APP
                else -> OTHER
            }
        }

        /** Lower-case extension without the dot, or "" when there is none. A leading dot alone (".nomedia") is not an extension. */
        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.lastIndex) return ""
            return name.substring(dot + 1).lowercase()
        }

        /**
         * The text drawn on a badge: the extension itself, up to four
         * characters, because "HEIC" or "XLSX" tells Pat more than a generic
         * picture glyph would. Falls back to a short kind label.
         */
        fun badgeText(name: String, kind: FileKind): String {
            val ext = extensionOf(name)
            if (ext.isNotEmpty() && ext.length <= 4) return ext.uppercase()
            return when (kind) {
                FOLDER -> "DIR"
                IMAGE -> "IMG"
                VIDEO -> "VID"
                AUDIO -> "AUD"
                ARCHIVE -> "ZIP"
                APP -> "APK"
                else -> if (ext.isNotEmpty()) ext.take(3).uppercase() else "FILE"
            }
        }
    }
}
