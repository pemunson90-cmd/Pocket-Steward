package com.pocketsteward.app.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.pocketsteward.app.data.db.FileRecord
import java.io.File

data class MetadataEnrichment(
    val record: FileRecord,
    val changed: Boolean,
    val fieldsAdded: Set<String> = emptySet(),
)

/**
 * Cheap Level-1 metadata enrichment. It never decodes full-size images and
 * never reads whole media files into memory.
 */
class MetadataEnricher(
    private val context: Context,
) {
    fun supports(record: FileRecord): Boolean {
        if (record.isDirectory) return false
        val ext = record.extension.lowercase()
        return ext in IMAGE_EXTENSIONS || ext in MEDIA_EXTENSIONS || ext == "apk"
    }

    fun enrich(record: FileRecord): MetadataEnrichment {
        if (!supports(record)) return MetadataEnrichment(record, changed = false)
        val file = File(record.stableRef)
        if (!file.isFile) return MetadataEnrichment(record, changed = false)

        var updated = record
        val fields = linkedSetOf<String>()

        if (record.extension.lowercase() in IMAGE_EXTENSIONS) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
            if (options.outWidth > 0 && options.outHeight > 0 &&
                (record.width != options.outWidth || record.height != options.outHeight)
            ) {
                updated = updated.copy(width = options.outWidth, height = options.outHeight)
                fields += "dimensions"
            }
        }

        if (record.extension.lowercase() in MEDIA_EXTENSIONS) {
            val duration = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }
            }.getOrNull()
            if (duration != null && duration >= 0 && record.durationMs != duration) {
                updated = updated.copy(durationMs = duration)
                fields += "duration"
            }
        }

        if (record.extension.equals("apk", ignoreCase = true)) {
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.getPackageArchiveInfo(
                        file.absolutePath,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                }
            }.getOrNull()
            val packageName = info?.packageName
            val versionName = info?.versionName
            if (!packageName.isNullOrBlank() &&
                (record.apkPackageName != packageName || record.apkVersionName != versionName)
            ) {
                updated = updated.copy(
                    apkPackageName = packageName,
                    apkVersionName = versionName,
                )
                fields += "apk"
            }
        }

        return MetadataEnrichment(
            record = updated,
            changed = updated != record,
            fieldsAdded = fields,
        )
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
        )
        val MEDIA_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg",
            "mp4", "m4v", "mkv", "webm", "3gp", "mov",
        )
    }
}
