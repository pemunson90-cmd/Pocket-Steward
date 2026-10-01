package com.pocketsteward.app.image

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.similarity.ImageDHash
import kotlinx.coroutines.tasks.await
import java.io.File

data class ImageLabelScore(
    val label: String,
    val confidence: Float,
)

data class ImageInsight(
    val stableRef: String,
    val displayName: String,
    val labels: List<ImageLabelScore>,
    val likelyScreenshot: Boolean,
)

/**
 * Fully on-device image understanding using ML Kit's bundled base label model.
 * It is opt-in behind the existing Image analysis privacy switch.
 */
class ImageUnderstanding(
    private val context: Context,
) {
    suspend fun analyze(record: FileRecord): ImageInsight? {
        if (record.isDirectory) return null
        val uri = when {
            record.stableRef.startsWith("content://") -> Uri.parse(record.stableRef)
            else -> {
                val file = File(record.stableRef)
                if (!file.isFile) return null
                Uri.fromFile(file)
            }
        }

        readCache(record)?.let { return it }
        // Decode a bounded bitmap instead of holding a full camera photo in memory.
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull() ?: return null
        val task = labeler.process(InputImage.fromBitmap(bitmap, 0))
        return try {
            val labels = task.await()
                .sortedByDescending { it.confidence }.take(MAX_LABELS)
                .map { ImageLabelScore(it.text, it.confidence) }
            ImageInsight(record.stableRef, record.displayName, labels, ImageDHash.likelyScreenshot(record.displayName))
                .also { writeCache(record, it) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } finally {
            if (task.isComplete) bitmap.recycle() else task.addOnCompleteListener { bitmap.recycle() }
        }
    }

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build())
    }
    private fun cacheFile(record: FileRecord): File {
        val key = MessageDigest.getInstance("SHA-256").digest(record.stableRef.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "image-evidence-v1"), "$key.json")
    }
    private fun readCache(record: FileRecord): ImageInsight? = runCatching {
        if (record.modifiedAt == null) return null
        val file = cacheFile(record)
        if (!file.isFile || file.length() > 32_768) return null
        val json = JSONObject(file.readText())
        if (json.getLong("size") != record.sizeBytes || json.getLong("modified") != record.modifiedAt) return null
        val labels = json.getJSONArray("labels")
        require(labels.length() <= MAX_LABELS)
        ImageInsight(record.stableRef, record.displayName, (0 until labels.length()).map {
            val value = labels.getJSONObject(it)
            ImageLabelScore(value.getString("label"), value.getDouble("confidence").toFloat())
        }, ImageDHash.likelyScreenshot(record.displayName))
    }.getOrNull()
    private fun writeCache(record: FileRecord, insight: ImageInsight) {
        if (record.modifiedAt == null) return
        runCatching {
            val labels = JSONArray()
            insight.labels.forEach { labels.put(JSONObject().put("label", it.label).put("confidence", it.confidence.toDouble())) }
            val file = cacheFile(record)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
            try {
                temporary.writeText(JSONObject().put("size", record.sizeBytes).put("modified", record.modifiedAt).put("labels", labels).toString())
                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }

    fun perceptualHash(record: FileRecord): Long? =
        ImageDHash.fromRef(context, record.stableRef)

    companion object {
        const val MIN_CONFIDENCE = 0.60f
        const val MAX_LABELS = 5
    }
}
