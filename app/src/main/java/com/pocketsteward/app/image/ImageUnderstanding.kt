package com.pocketsteward.app.image

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
        val file = File(record.stableRef)
        if (!file.isFile) return null

        val input = runCatching {
            InputImage.fromFilePath(context, Uri.fromFile(file))
        }.getOrNull() ?: return null

        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_CONFIDENCE)
                .build(),
        )

        return try {
            val labels = labeler.process(input).await()
                .sortedByDescending { it.confidence }
                .take(MAX_LABELS)
                .map { ImageLabelScore(it.text, it.confidence) }

            ImageInsight(
                stableRef = record.stableRef,
                displayName = record.displayName,
                labels = labels,
                likelyScreenshot = ImageDHash.likelyScreenshot(record.displayName),
            )
        } catch (_: Throwable) {
            null
        } finally {
            labeler.close()
        }
    }

    companion object {
        const val MIN_CONFIDENCE = 0.60f
        const val MAX_LABELS = 5
    }
}
