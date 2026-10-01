package com.pocketsteward.app.filing

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileMetadata

/** Live source metadata must precede all reuse of derived evidence. */
object FilingFreshness {
    fun refresh(record: FileRecord, live: FileMetadata): FileRecord {
        require(record.isDirectory == live.isDirectory) { "Source type changed. Refresh the scan before filing." }
        val changed = live.modifiedAtEpochMs == null || record.modifiedAt != live.modifiedAtEpochMs || record.sizeBytes != live.sizeBytes || record.extension != live.extension
        val updated = record.copy(displayName = live.displayName, extension = live.extension, mimeType = live.mimeType, sizeBytes = live.sizeBytes, createdAt = live.createdAtEpochMs, modifiedAt = live.modifiedAtEpochMs, isHidden = live.isHidden)
        return if (!changed) updated else updated.copy(mediaType = null, width = null, height = null, durationMs = null, apkPackageName = null, apkVersionName = null, sha256 = null, quickFingerprint = null, textPreview = null, classification = null, classificationConfidence = null)
    }
}
