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

data class FolderProtectionCandidate(
    val id: String,
    val displayName: String,
    val sampleEntries: List<String>,
)

data class FolderProtectionSuggestion(
    val id: String,
    val shouldProtect: Boolean,
    val reason: String,
)

interface AgentModel {
    suspend fun availability(): AgentModelAvailability
    fun download(): kotlinx.coroutines.flow.Flow<AgentModelDownloadState>
    suspend fun coherenceAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): CoherenceAuditResult

    /**
     * Read-only folder-coherence suggestion. The model returns advice only.
     * A caller that turns a suggestion into a marker file must still build a
     * PlannedOperation.WriteTextFile and pass validator -> preview -> executor.
     */
    suspend fun suggestFolderProtection(
        scopeLabel: String,
        folders: List<FolderProtectionCandidate>,
    ): List<FolderProtectionSuggestion> = emptyList()

    /**
     * Optional language-only fallback. The model may rewrite an unsupported
     * request into Pocket Steward's bounded command grammar, but it cannot
     * return PlannedOperation or touch storage. Callers must parse the result
     * again with DeterministicIntentParser before doing anything.
     */
    suspend fun normalizeIntentRequest(request: String): String? = null

    /**
     * Read-only question answering over excerpts Pocket Steward already
     * retrieved from the local index. The model sees only those excerpts,
     * must cite them, and its reply is checked by [AskAnswerProtocol].
     * Null means no model answer (unavailable or unusable reply); callers
     * then show the excerpts on their own.
     */
    suspend fun answerFromPassages(
        question: String,
        passages: List<com.pocketsteward.app.content.ask.AskPassage>,
    ): AskModelAnswer? = null
}
