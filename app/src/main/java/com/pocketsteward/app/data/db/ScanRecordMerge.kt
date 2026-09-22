package com.pocketsteward.app.data.db

/**
 * Merge policy for a Level-0 re-scan.
 *
 * Expensive Level-1/2 fields survive only when the cheap identity facts that
 * invalidate them are unchanged. A changed size or modified timestamp drops
 * hashes/content/derived metadata so later on-demand work cannot reuse stale
 * evidence.
 */
fun mergeScanRecord(existing: FileRecord, scanned: FileRecord): FileRecord {
    val contentUnchanged =
        existing.sizeBytes == scanned.sizeBytes &&
            existing.modifiedAt == scanned.modifiedAt &&
            existing.isDirectory == scanned.isDirectory

    if (!contentUnchanged) {
        return scanned.copy(id = existing.id)
    }

    return scanned.copy(
        id = existing.id,
        mediaType = existing.mediaType,
        width = existing.width,
        height = existing.height,
        durationMs = existing.durationMs,
        apkPackageName = existing.apkPackageName,
        apkVersionName = existing.apkVersionName,
        sha256 = existing.sha256,
        quickFingerprint = existing.quickFingerprint,
        textPreview = existing.textPreview,
        classification = existing.classification,
        classificationConfidence = existing.classificationConfidence,
    )
}
