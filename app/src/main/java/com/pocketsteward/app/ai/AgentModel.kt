package com.pocketsteward.app.ai

enum class AgentModelAvailability {
    UNAVAILABLE,
    DOWNLOADABLE,
    DOWNLOADING,
    AVAILABLE,
}

sealed interface AgentModelDownloadState {
    data class Started(val bytesToDownload: Long) : AgentModelDownloadState
    data class Progress(val bytesDownloaded: Long) : AgentModelDownloadState
    data object Completed : AgentModelDownloadState
    data class Failed(val message: String) : AgentModelDownloadState
}

enum class CoherenceClass {
    BELONGS,
    QUESTIONABLE,
    DOES_NOT_BELONG,
    UNCERTAIN,
}

data class SemanticDocument(
    val id: String,
    val displayName: String,
    val sourcePath: String,
    val excerpt: String,
)

data class CoherenceFinding(
    val id: String,
    val classification: CoherenceClass,
    val reason: String,
    val suggestedGroup: String?,
)

data class CoherenceAuditResult(
    val findings: List<CoherenceFinding>,
    val modelName: String?,
    val limited: Boolean,
)

interface AgentModel {
    suspend fun availability(): AgentModelAvailability
    fun download(): kotlinx.coroutines.flow.Flow<AgentModelDownloadState>
    suspend fun coherenceAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): CoherenceAuditResult
}
