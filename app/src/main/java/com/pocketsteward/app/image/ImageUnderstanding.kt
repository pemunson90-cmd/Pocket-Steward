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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pocketsteward.app.storage.FileMetadata
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
    val width: Int = 0,
    val height: Int = 0,
    val detectedText: String = "",
    val textInspectionEnabled: Boolean = false,
    val textInspectionComplete: Boolean = false,
    val textTruncated: Boolean = false,
    val screenshotEvidence: String? = null,
    val description: String = "",
)

/**
 * Fully on-device image understanding using ML Kit's bundled base label model.
 * It is opt-in behind the existing Image analysis privacy switch.
 */
class ImageUnderstanding(
    private val context: Context,
    private val observeMetadata: suspend (String) -> FileMetadata,
) : ImageEvidenceSource {
    private val attempts = ImageAttemptStore(File(context.noBackupFilesDir, "image-attempts-v2"))

    override suspend fun cached(record: FileRecord, inspectText: Boolean): ImageInsight? {
        currentCoroutineContext().ensureActive()
        val live = try { observeMetadata(record.stableRef) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return null }
        if (live.isDirectory || live.displayName != record.displayName || live.sizeBytes != record.sizeBytes || live.modifiedAtEpochMs != record.modifiedAt) return null
        return readCache(record, inspectText)
    }
    override suspend fun attemptedAt(record: FileRecord, inspectText: Boolean): Long? = attempts.read(record, inspectText)?.at
    override suspend fun unavailable(record: FileRecord, inspectText: Boolean): Boolean =
        record.modifiedAt != null && attempts.read(record, inspectText)?.outcome == ImageAttemptOutcome.UNAVAILABLE
    override suspend fun noteOutcome(record: FileRecord, inspectText: Boolean, insight: ImageInsight?): Boolean {
        val visual = attempts.write(record, false, if (insight == null) ImageAttemptOutcome.UNAVAILABLE else ImageAttemptOutcome.SUCCEEDED)
        return if (inspectText) attempts.write(record, true,
            if (insight?.textInspectionComplete == true) ImageAttemptOutcome.SUCCEEDED else ImageAttemptOutcome.UNAVAILABLE) && visual else visual
    }
    override suspend fun markAttempt(record: FileRecord, inspectText: Boolean): Boolean {
        val visual = attempts.write(record, false)
        return if (inspectText) attempts.write(record, true) && visual else visual
    }

    override suspend fun analyze(record: FileRecord, inspectText: Boolean, allowFresh: Boolean): ImageInsight? {
        currentCoroutineContext().ensureActive()
        if (record.isDirectory || record.sizeBytes > 64L * 1024 * 1024) return null
        fun matches(live: FileMetadata) = !live.isDirectory && live.displayName == record.displayName &&
            live.sizeBytes == record.sizeBytes && live.modifiedAtEpochMs == record.modifiedAt
        try { if (!matches(observeMetadata(record.stableRef))) return null }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return null }
        readCache(record, inspectText)?.let { return it }
        if (!allowFresh) return null
        val cachedVisual = if (inspectText) readCache(record, false) else null
        val uri = if (record.stableRef.startsWith("content://")) Uri.parse(record.stableRef) else Uri.fromFile(File(record.stableRef))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) > 100_000) return null
            var sample = 1
            val maximum = if (inspectText) 1600 else 1024
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximum) sample *= 2
            currentCoroutineContext().ensureActive()
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null } ?: return null
        var pending: Task<*>? = null
        return try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val labels = cachedVisual?.labels ?: labeler.process(input).also { pending = it }.await()
                .sortedByDescending { it.confidence }.take(MAX_LABELS).map { ImageLabelScore(it.text, it.confidence) }
            var text = ""
            var textComplete = false
            if (inspectText) {
                try {
                    currentCoroutineContext().ensureActive()
                    text = recognizer.process(input).also { pending = it }.await().text
                    textComplete = true
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Labels remain useful if OCR is unavailable. */ }
            }
            currentCoroutineContext().ensureActive()
            if (!matches(observeMetadata(record.stableRef))) return null
            currentCoroutineContext().ensureActive()
            val bounded = ImageEvidencePolicy.boundedText(text)
            val screenshot = ImageEvidencePolicy.screenshotEvidence(record.displayName, bounds.outWidth, bounds.outHeight, bounded)
            ImageInsight(record.stableRef, record.displayName, labels, screenshot != null,
                bounds.outWidth, bounds.outHeight, bounded, inspectText, textComplete,
                text.length > ImageEvidencePolicy.MAX_TEXT_CHARS, screenshot,
                ImageEvidencePolicy.description(bounds.outWidth, bounds.outHeight, labels, screenshot, bounded))
                .also {
                    val visualScreenshot = ImageEvidencePolicy.screenshotEvidence(record.displayName, it.width, it.height, "")
                    writeCache(record, it.copy(likelyScreenshot = visualScreenshot != null, detectedText = "", textInspectionEnabled = false,
                        textInspectionComplete = false, textTruncated = false, screenshotEvidence = visualScreenshot,
                        description = ImageEvidencePolicy.description(it.width, it.height, it.labels, visualScreenshot, "")), false)
                    if (inspectText && textComplete) writeCache(record, it, true)
                }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
        finally {
            val task = pending
            if (task == null || task.isComplete) bitmap.recycle() else task.addOnCompleteListener { bitmap.recycle() }
        }
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build())
    }
    private fun cacheFile(record: FileRecord, inspectText: Boolean): File {
        val key = MessageDigest.getInstance("SHA-256").digest(record.stableRef.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "image-evidence-v2"), "$key-${if (inspectText) "text" else "labels"}.json")
    }
    private fun readCache(record: FileRecord, inspectText: Boolean): ImageInsight? = runCatching {
        if (record.modifiedAt == null) return null
        val file = cacheFile(record, inspectText)
        if (!file.isFile || file.length() > 32_768) return null
        val json = JSONObject(file.readText())
        if (json.getLong("size") != record.sizeBytes || json.getLong("modified") != record.modifiedAt) return null
        val labels = json.getJSONArray("labels")
        require(labels.length() <= MAX_LABELS)
        val scores = (0 until labels.length()).map {
            val value = labels.getJSONObject(it)
            ImageLabelScore(value.getString("label").take(160), value.getDouble("confidence").toFloat())
        }
        require(scores.all { it.confidence.isFinite() && it.confidence in MIN_CONFIDENCE..1f })
        val text = if (inspectText) ImageEvidencePolicy.boundedText(json.optString("text")) else ""
        val width = json.getInt("width")
        val height = json.getInt("height")
        require(width in 1..100_000 && height in 1..100_000)
        val screenshot = ImageEvidencePolicy.screenshotEvidence(record.displayName, width, height, text)
        ImageInsight(record.stableRef, record.displayName, scores, screenshot != null, width, height, text,
            inspectText, inspectText, inspectText && json.optBoolean("textTruncated"), screenshot,
            ImageEvidencePolicy.description(width, height, scores, screenshot, text))
    }.getOrNull()
    private fun writeCache(record: FileRecord, insight: ImageInsight, inspectText: Boolean) {
        if (record.modifiedAt == null) return
        runCatching {
            val labels = JSONArray()
            insight.labels.forEach { labels.put(JSONObject().put("label", it.label).put("confidence", it.confidence.toDouble())) }
            val file = cacheFile(record, inspectText)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
            try {
                temporary.writeText(JSONObject().put("size", record.sizeBytes).put("modified", record.modifiedAt).put("labels", labels).put("width", insight.width).put("height", insight.height)
                    .put("text", if (inspectText) insight.detectedText else "").put("textTruncated", insight.textTruncated).toString())
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
