package com.pocketsteward.app.image

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.evidence.EvidenceFingerprint
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ImageCacheFreshnessTest {
    @Test fun changedImageBytesWithSameSizeAndTimestampInvalidateLabelsOcrAndAttemptHints() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "sample-${UUID.randomUUID()}.jpg")
        source.writeBytes(ByteArray(100) { 1 }); source.setLastModified(1000)
        val record = FileRecord(stableRef = source.absolutePath, displayName = source.name, extension = "jpg", mimeType = "image/jpeg",
            absolutePathOrUri = source.absolutePath, parentRef = source.parent, sizeBytes = 100, createdAt = null, modifiedAt = source.lastModified(),
            lastScannedAt = 0, isDirectory = false, isHidden = false)
        fun fingerprint() = source.inputStream().use { EvidenceFingerprint.read(it, 100) }
        val model = ImageUnderstanding(context, readFingerprint = { fingerprint() }, observeMetadata = {
            FileMetadata(FileRef.Direct(it), source.name, "jpg", "image/jpeg", source.length(), null, source.lastModified(), false, false)
        })
        val hash = MessageDigest.getInstance("SHA-256").digest(source.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "image-evidence-v2").apply { mkdirs() }
        val labels = File(directory, "$hash-labels.json")
        val text = File(directory, "$hash-text.json")
        val fixture = JSONObject().put("version", 3).put("sample", fingerprint()).put("size", 100).put("modified", record.modifiedAt)
            .put("width", 100).put("height", 100).put("text", "Lilith manuscript").put("textTruncated", false)
            .put("labels", JSONArray().put(JSONObject().put("label", "Landscape").put("confidence", 0.9)))
        labels.writeText(fixture.toString()); text.writeText(fixture.toString())
        try {
            assertThat(model.cached(record, false)?.labels?.first()?.label).isEqualTo("Landscape")
            assertThat(model.cached(record, true)?.detectedText).isEqualTo("Lilith manuscript")
            assertThat(model.markAttempt(record, true)).isTrue()
            assertThat(model.noteOutcome(record, true, null)).isTrue()
            assertThat(model.unavailable(record, true)).isTrue()
            source.writeBytes(ByteArray(100) { 2 }); source.setLastModified(requireNotNull(record.modifiedAt))
            assertThat(model.cached(record, false)).isNull()
            assertThat(model.cached(record, true)).isNull()
            assertThat(model.unavailable(record, true)).isFalse()
            assertThat(model.attemptedAt(record, true)).isNull()
        } finally { source.delete(); labels.delete(); text.delete() }
    }
}
