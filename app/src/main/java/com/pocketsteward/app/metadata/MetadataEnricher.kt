package com.pocketsteward.app.metadata

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import androidx.exifinterface.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.pocketsteward.app.data.db.FileRecord
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class MetadataEnrichment(
    val record: FileRecord,
    val changed: Boolean,
    val fieldsAdded: Set<String> = emptySet(),
    val pdfPageCount: Int? = null,
    val archiveEntryCount: Int? = null,
    val archiveSample: List<String> = emptyList(),
    val apkLabel: String? = null,
    val apkVersionCode: Long? = null,
    val exifCamera: String? = null,
    val exifOrientation: String? = null,
)

/**
 * Cheap Level-1 metadata enrichment for both direct paths and persisted SAF
 * document URIs. It never decodes a full-size image and never reads a whole
 * media/archive file into memory.
 *
 * Persistable fields reuse FileRecord's existing Level-1 columns. Transient
 * details such as PDF page count and ZIP entry samples are returned to the
 * review UI without forcing another Room migration.
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
        if (!record.stableRef.startsWith("content://") && !File(record.stableRef).isFile) {
            return MetadataEnrichment(record, changed = false)
        }

        var updated = record
        val fields = linkedSetOf<String>()
        var pdfPages: Int? = null
        var archiveCount: Int? = null
        var archiveSample = emptyList<String>()
        var apkLabel: String? = null
        var apkVersionCode: Long? = null
        var camera: String? = null
        var orientation: String? = null

        if (record.extension.lowercase() in IMAGE_EXTENSIONS) {
            imageBounds(record)?.let { (width, height) ->
                if (record.width != width || record.height != height) {
                    updated = updated.copy(width = width, height = height)
                    fields += "dimensions"
                }
            }

            exif(record)?.let { metadata ->
                camera = metadata.first
                orientation = metadata.second
            }
        }

        if (record.extension.lowercase() in MEDIA_EXTENSIONS) {
            val duration = mediaDuration(record)
            if (duration != null && duration >= 0 && record.durationMs != duration) {
                updated = updated.copy(durationMs = duration)
                fields += "duration"
            }
        }

        if (record.extension.equals("apk", ignoreCase = true)) {
            apkInfo(record)?.let { info ->
                val packageName = info.packageName
                val versionName = info.versionName
                apkLabel = archiveLabel(info, record)
                apkVersionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                if (packageName.isNotBlank() &&
                    (record.apkPackageName != packageName || record.apkVersionName != versionName)
                ) {
                    updated = updated.copy(
                        apkPackageName = packageName,
                        apkVersionName = versionName,
                    )
                    fields += "apk"
                }
            }
        }

        if (record.extension.equals("pdf", ignoreCase = true)) {
            pdfPages = pdfPageCount(record)
        }

        if (record.extension.equals("zip", ignoreCase = true)) {
            zipSummary(record)?.let { (count, sample) ->
                archiveCount = count
                archiveSample = sample
            }
        }

        return MetadataEnrichment(
            record = updated,
            changed = updated != record,
            fieldsAdded = fields,
            pdfPageCount = pdfPages,
            archiveEntryCount = archiveCount,
            archiveSample = archiveSample,
            apkLabel = apkLabel,
            apkVersionCode = apkVersionCode,
            exifCamera = camera,
            exifOrientation = orientation,
        )
    }

    private fun imageBounds(record: FileRecord): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return if (record.isSaf()) {
            runCatching {
                context.contentResolver.openInputStream(record.uri())?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth to options.outHeight
                } else {
                    null
                }
            }.getOrNull()
        } else {
            runCatching {
                BitmapFactory.decodeFile(record.stableRef, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth to options.outHeight
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    private fun exif(record: FileRecord): Pair<String?, String?>? = runCatching {
        val read: (ExifInterface) -> Pair<String?, String?> = { exif ->
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            val camera = listOfNotNull(make, model)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
                .takeIf { it.isNotBlank() }

            val orientation = when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            ) {
                ExifInterface.ORIENTATION_NORMAL -> "Normal"
                ExifInterface.ORIENTATION_ROTATE_90 -> "Rotate 90°"
                ExifInterface.ORIENTATION_ROTATE_180 -> "Rotate 180°"
                ExifInterface.ORIENTATION_ROTATE_270 -> "Rotate 270°"
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Flip horizontal"
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flip vertical"
                else -> null
            }
            camera to orientation
        }

        if (record.isSaf()) {
            context.contentResolver.openInputStream(record.uri())?.use { input ->
                read(ExifInterface(input))
            }
        } else {
            read(ExifInterface(record.stableRef))
        }
    }.getOrNull()

    private fun mediaDuration(record: FileRecord): Long? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            if (record.isSaf()) {
                retriever.setDataSource(context, record.uri())
            } else {
                retriever.setDataSource(record.stableRef)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()

    private fun apkInfo(record: FileRecord): PackageInfo? {
        if (!record.isSaf()) return packageInfoForPath(record.stableRef)
        if (record.sizeBytes > MAX_APK_METADATA_BYTES) return null

        val temp = File.createTempFile("pocket-steward-apk-", ".apk", context.cacheDir)
        return try {
            val copied = runCatching {
                context.contentResolver.openInputStream(record.uri())?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                } != null
            }.getOrDefault(false)
            if (!copied) null else packageInfoForPath(temp.absolutePath)
        } finally {
            temp.delete()
        }
    }

    private fun packageInfoForPath(path: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                path,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(path, 0)
        }
    }.getOrNull()

    private fun archiveLabel(info: PackageInfo, record: FileRecord): String? = runCatching {
        val applicationInfo = info.applicationInfo ?: return@runCatching null
        val path = if (record.isSaf()) null else record.stableRef
        if (path != null) {
            applicationInfo.sourceDir = path
            applicationInfo.publicSourceDir = path
        }
        context.packageManager.getApplicationLabel(applicationInfo).toString().trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun pdfPageCount(record: FileRecord): Int? = runCatching {
        if (record.isSaf()) {
            context.contentResolver.openFileDescriptor(record.uri(), "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            }
        } else {
            ParcelFileDescriptor.open(
                File(record.stableRef),
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            }
        }
    }.getOrNull()

    private fun zipSummary(record: FileRecord): Pair<Int, List<String>>? =
        if (record.isSaf()) {
            runCatching {
                context.contentResolver.openInputStream(record.uri())?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        val names = mutableListOf<String>()
                        var count = 0
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            count++
                            if (names.size < MAX_ARCHIVE_SAMPLE) names += entry.name
                            zip.closeEntry()
                        }
                        count to names
                    }
                }
            }.getOrNull()
        } else {
            runCatching {
                ZipFile(File(record.stableRef)).use { zip ->
                    val names = mutableListOf<String>()
                    var count = 0
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        count++
                        if (names.size < MAX_ARCHIVE_SAMPLE) names += entry.name
                    }
                    count to names
                }
            }.getOrNull()
        }

    private fun FileRecord.isSaf(): Boolean = stableRef.startsWith("content://")
    private fun FileRecord.uri(): Uri = Uri.parse(stableRef)

    private companion object {
        const val MAX_ARCHIVE_SAMPLE = 40
        const val MAX_APK_METADATA_BYTES = 256L * 1024L * 1024L

        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
        )
        val MEDIA_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg",
            "mp4", "m4v", "mkv", "webm", "3gp", "mov",
        )
    }
}