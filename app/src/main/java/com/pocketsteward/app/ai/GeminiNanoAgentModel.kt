package com.pocketsteward.app.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Gemini Nano implementation of the read-only AgentModel boundary.
 *
 * It receives text prepared by Pocket Steward and returns classifications.
 * It never receives StorageGateway, PlannedOperation, or executor access.
 */
class GeminiNanoAgentModel : AgentModel {
    private val model by lazy { Generation.getClient() }

    override suspend fun availability(): AgentModelAvailability = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> AgentModelAvailability.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> AgentModelAvailability.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> AgentModelAvailability.DOWNLOADING
        else -> AgentModelAvailability.UNAVAILABLE
    }

    override fun download(): Flow<AgentModelDownloadState> =
        model.download().map { status ->
            when (status) {
                is DownloadStatus.DownloadStarted ->
                    AgentModelDownloadState.Started(status.bytesToDownload)
                is DownloadStatus.DownloadProgress ->
                    AgentModelDownloadState.Progress(status.totalBytesDownloaded)
                DownloadStatus.DownloadCompleted ->
                    AgentModelDownloadState.Completed
                is DownloadStatus.DownloadFailed ->
                    AgentModelDownloadState.Failed(status.e.message ?: "Model download failed.")
            }
        }

    override suspend fun coherenceAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): CoherenceAuditResult {
        require(documents.isNotEmpty()) { "A coherence audit needs at least one readable document." }
        check(model.checkStatus() == FeatureStatus.AVAILABLE) { "Gemini Nano is not available yet." }
        check(model.isStructuredOutputFeatureAvailable()) {
            "Structured output is not available on this device/model configuration."
        }

        val bounded = documents.take(MAX_DOCUMENTS)
        val prompt = buildString {
            appendLine("You are performing a read-only folder coherence audit for Pocket Steward.")
            appendLine("Folder/scope: $scopeLabel")
            appendLine("Classify each document relative to the apparent themes of the set.")
            appendLine("Use BELONGS, QUESTIONABLE, DOES_NOT_BELONG, or UNCERTAIN.")
            appendLine("For QUESTIONABLE or DOES_NOT_BELONG, suggest a short destination/group name when useful.")
            appendLine("Do not invent file contents. Preserve every id exactly.")
            appendLine()
            bounded.forEach { doc ->
                appendLine("ID: ${doc.id}")
                appendLine("NAME: ${doc.displayName}")
                appendLine("PATH: ${doc.sourcePath}")
                appendLine("EXCERPT:")
                appendLine(doc.excerpt)
                appendLine("---")
            }
        }

        val base = generateContentRequest(TextPart(prompt)) {
            temperature = 0.2f
            maxOutputTokens = 1800
            candidateCount = 1
        }
        val typed = generateTypedContentRequest(base, CoherenceAuditOutput::class)
        val response = model.generateContent(typed)
        val output = response.candidates.firstOrNull()?.response
            ?: error("Gemini Nano returned no structured audit result.")

        val allowedIds = bounded.mapTo(hashSetOf()) { it.id }
        val findings = output.findings.mapNotNull { item ->
            if (item.id !in allowedIds) return@mapNotNull null
            val classification = runCatching { CoherenceClass.valueOf(item.classification.trim().uppercase()) }
                .getOrDefault(CoherenceClass.UNCERTAIN)
            CoherenceFinding(
                id = item.id,
                classification = classification,
                reason = item.reason.trim().take(400),
                suggestedGroup = item.suggestedGroup.trim().take(80).ifBlank { null },
            )
        }

        return CoherenceAuditResult(
            findings = findings,
            modelName = runCatching { model.getBaseModelName() }.getOrNull(),
            limited = documents.size > bounded.size,
        )
    }

    private companion object {
        const val MAX_DOCUMENTS = 20
    }
}
