package com.pocketsteward.app.data.db

/**
 * Merge policy for a Level-0 re-scan.
 *
 * Expensive Level-1/2 fields survive only when the cheap identity facts that
 * invalidate them are unchanged. A changed size or modified timestamp drops
 * hashes/content/derived metadata so later on-demand work cannot reuse stale
 * evidence.
 */
fun mergeScanRecord(existing: FileRecord, rawScanned: FileRecord): FileRecord {
    // Two walks can overlap (the background library over all storage and a
    // folder scan inside it). Keeping the newest sighting means a walk that
    // started earlier cannot stamp a file back to its older start time and
    // make the later walk think the file vanished.
    val scanned = rawScanned.copy(lastScannedAt = maxOf(existing.lastScannedAt, rawScanned.lastScannedAt))
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
