package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local index row for one scanned file or directory (plan Section 7).
 * Level 1/2 fields (dimensions, apk metadata, hashes, text preview,
 * classification) are nullable and populated lazily — never eagerly during
 * the cheap Level 0 inventory pass.
 *
 * [stableRef] is unique so re-scanning a file replaces its existing row
 * (via OnConflictStrategy.REPLACE against this index) instead of inserting
 * a duplicate every time a scan resumes or re-runs.
 *
 * [scopeRootRef] tags which scan root (e.g. the resolved Downloads folder,
 * or a SAF tree URI) this record was found under, so a Scan Summary query
 * can select "everything under this scope" without relying on path-prefix
 * matching, which isn't reliable for SAF content URIs.
 */
@Entity(
    tableName = "file_records",
    indices = [Index(value = ["stableRef"], unique = true), Index(value = ["scopeRootRef"])],
)
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableRef: String,
    val scopeRootRef: String,
    val displayName: String,
    val extension: String,
    val mimeType: String?,
    val absolutePathOrUri: String,
    val parentRef: String?,
    val sizeBytes: Long,
    val createdAt: Long?,
    val modifiedAt: Long?,
    val lastScannedAt: Long,
    val isDirectory: Boolean,
    val isHidden: Boolean,

    // Level 1 — populated on demand.
    val mediaType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val apkPackageName: String? = null,
    val apkVersionName: String? = null,

    // Level 2 — populated only when necessary.
    val sha256: String? = null,
    val quickFingerprint: String? = null,
    val textPreview: String? = null,
    val classification: String? = null,
    val classificationConfidence: Float? = null,
)
