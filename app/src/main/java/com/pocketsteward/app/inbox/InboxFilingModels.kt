package com.pocketsteward.app.inbox

import com.pocketsteward.app.saved.ProjectHome

enum class FilingConfidence {
    STRONG,
    PROBABLE,
    UNRESOLVED,
}

enum class FilingEvidenceType {
    USER_MAPPING,
    KNOWN_PROJECT_HOME,
    APK_LABEL,
    APK_PACKAGE,
    APK_VERSION,
    FILENAME,
    ARCHIVE_ENTRY,
    INDEXED_CONTENT,
    VERSION_TOKEN,
    COHORT_TIME,
    EXISTING_FOLDER,
}

data class FilingEvidence(
    val type: FilingEvidenceType,
    val detail: String,
    val weight: Int,
)

data class ArtifactSignals(
    val stableRef: String,
    val displayName: String,
    val extension: String,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val apkLabel: String? = null,
    val apkPackageName: String? = null,
    val apkVersionName: String? = null,
    val apkVersionCode: Long? = null,
    val apkSignerSha256: String? = null,
    val archiveSample: List<String> = emptyList(),
    val indexedText: String = "",
)

data class FilingDecision(
    val artifact: ArtifactSignals,
    val projectHome: ProjectHome,
    val release: String?,
    val confidence: FilingConfidence,
    val evidence: List<FilingEvidence>,
)

data class UnresolvedArtifact(
    val artifact: ArtifactSignals,
    val reason: String,
)

data class InboxFilingAnalysis(
    val decisions: List<FilingDecision>,
    val unresolved: List<UnresolvedArtifact>,
)

/** Review-only metadata; the executor still sees ordinary PlannedOperation values. */
data class FilingUiHint(
    val sourceRef: String,
    val projectName: String,
    val projectHomePath: String,
    val release: String?,
    val destinationPath: String,
    val confidence: FilingConfidence,
    val evidence: List<String>,
    val sizeBytes: Long,
    val packageId: String? = null,
    val homeAlreadyExisted: Boolean = true,
    val releaseFolderAlreadyExisted: Boolean = true,
)
