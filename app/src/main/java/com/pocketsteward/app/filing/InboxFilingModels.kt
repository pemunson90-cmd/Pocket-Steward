package com.pocketsteward.app.filing

import com.pocketsteward.app.saved.ProjectHierarchyStrategy

enum class FilingConfidence {
    STRONG,
    PROBABLE,
    UNRESOLVED,
}

enum class FilingEvidenceKind {
    USER_MAPPING,
    PROJECT_HOME,
    APK_PACKAGE,
    APK_LABEL,
    APK_VERSION,
    FILENAME,
    ARCHIVE_ENTRY,
    INDEXED_CONTENT,
    VERSION_TOKEN,
    COHORT,
    DESTINATION_DUPLICATE,
    DESTINATION_CONFLICT,
    DESTINATION_PROTECTED,
}

data class FilingEvidence(
    val kind: FilingEvidenceKind,
    val detail: String,
    val weight: Int,
)

data class FilingArtifact(
    val stableRef: String,
    val displayName: String,
    val extension: String,
    val sizeBytes: Long,
    val createdAt: Long? = null,
    val modifiedAt: Long?,
    val parentRef: String?,
    val apkPackageName: String? = null,
    val apkVersionName: String? = null,
    val apkLabel: String? = null,
    val apkVersionCode: Long? = null,
    val archiveSample: List<String> = emptyList(),
    val indexedText: String = "",
)

data class ProjectHomeCandidate(
    val name: String,
    val path: String,
    val aliases: List<String> = emptyList(),
    val packageIds: List<String> = emptyList(),
    val hierarchy: ProjectHierarchyStrategy = ProjectHierarchyStrategy.VERSIONED,
    val persisted: Boolean = false,
)

data class FilingDecision(
    val artifact: FilingArtifact,
    val projectName: String?,
    val projectHome: ProjectHomeCandidate?,
    val release: String?,
    val destinationDirectory: String?,
    val confidence: FilingConfidence,
    val evidence: List<FilingEvidence>,
    val createsProjectHome: Boolean = false,
) {
    val evidenceSummary: String
        get() = evidence
            .sortedByDescending { it.weight }
            .take(3)
            .joinToString(" · ") { it.detail }
}

data class InboxFilingResult(
    val decisions: List<FilingDecision>,
) {
    val proposed: List<FilingDecision>
        get() = decisions.filter { it.confidence != FilingConfidence.UNRESOLVED && it.destinationDirectory != null }

    val unresolved: List<FilingDecision>
        get() = decisions.filter { it.confidence == FilingConfidence.UNRESOLVED || it.destinationDirectory == null }
}