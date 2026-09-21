package com.pocketsteward.app.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import com.pocketsteward.app.data.db.FileRecord
import java.io.File
import java.util.zip.ZipFile

data class MetadataEnrichment(
    val record: FileRecord,
    val changed: Boolean,
    val fieldsAdded: Set<String> = emptySet(),
    val pdfPageCount: Int? = null,
    val archiveEntryCount: Int? = null,
    val archiveSample: List<String> = emptyList(),
    val exifCamera: String? = null,
    val exifOrientation: String? = null,
)

/**
 * Cheap Level-1 metadata enrichment. It never decodes full-size images and
 * never reads whole media/archive files into memory. Persistable fields reuse
 * FileRecord's existing Level-1 columns; transient details are returned to
 * the review UI without a Room schema migration.
 */
class MetadataEnricher(
    private val context: Context,
) {
    fun supports(record: FileRecord): Boolean {
        if (record.isDirectory) return false
        val ext = record.extension.lowercase()
        return ext in IMAGE_EXTENSIONS ||
            ext in MEDIA_EXTENSIONS ||
            ext == "apk" ||
            ext == "pdf" ||
            ext == "zip"
    }

    fun enrich(record: FileRecord): MetadataEnrichment {
        if (!supports(record)) return MetadataEnrichment(record, changed = false)
        val file = File(record.stableRef)
        if (!file.isFile) return MetadataEnrichment(record, changed = false)

        var updated = record
        val fields = linkedSetOf<String>()
        var pdfPages: Int? = null
        var archiveCount: Int? = null
        var archiveSample = emptyList<String>()
        var camera: String? = null
        var orientation: String? = null

        if (record.extension.lowercase() in IMAGE_EXTENSIONS) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
            if (options.outWidth > 0 && options.outHeight > 0 &&
                (record.width != options.outWidth || record.height != options.outHeight)
            ) {
                updated = updated.copy(width = options.outWidth, height = options.outHeight)
                fields += "dimensions"
            }

            runCatching {
                ExifInterface(file.absolutePath).let { exif ->
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    camera = listOfNotNull(make, model)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" ")
                        .takeIf { it.isNotBlank() }
                    val rawOrientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED,
                    )
                    orientation = when (rawOrientation) {
                        ExifInterface.ORIENTATION_NORMAL -> "Normal"
                        ExifInterface.ORIENTATION_ROTATE_90 -> "Rotate 90°"
                        ExifInterface.ORIENTATION_ROTATE_180 -> "Rotate 180°"
                        ExifInterface.ORIENTATION_ROTATE_270 -> "Rotate 270°"
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Flip horizontal"
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flip vertical"
                        else -> null
                    }
                }
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

        if (record.extension.equals("pdf", ignoreCase = true)) {
            pdfPages = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
                }
            }.getOrNull()
        }

        if (record.extension.equals("zip", ignoreCase = true)) {
            runCatching {
                ZipFile(file).use { zip ->
                    val names = mutableListOf<String>()
                    var count = 0
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        count++
                        if (names.size < MAX_ARCHIVE_SAMPLE) names += entry.name
                    }
                    archiveCount = count
                    archiveSample = names
                }
            }
        }

        return MetadataEnrichment(
            record = updated,
            changed = updated != record,
            fieldsAdded = fields,
            pdfPageCount = pdfPages,
            archiveEntryCount = archiveCount,
            archiveSample = archiveSample,
            exifCamera = camera,
            exifOrientation = orientation,
        )
    }

    private companion object {
        const val MAX_ARCHIVE_SAMPLE = 12

        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
        )
        val MEDIA_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg",
            "mp4", "m4v", "mkv", "webm", "3gp", "mov",
        )
    }
}
