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

        val bounded = documents.take(MAX_DOCUMENTS)
        val findings = if (runCatching { model.isStructuredOutputFeatureAvailable() }.getOrDefault(false)) {
            structuredAudit(scopeLabel, bounded)
        } else {
            textFallbackAudit(scopeLabel, bounded)
        }

        return CoherenceAuditResult(
            findings = findings,
            modelName = runCatching { model.getBaseModelName() }.getOrNull(),
            limited = documents.size > bounded.size,
        )
    }

    private suspend fun structuredAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): List<CoherenceFinding> {
        val prompt = buildStructuredPrompt(scopeLabel, documents)
        val typed = generateTypedContentRequest(auditRequest(prompt), CoherenceAuditOutput::class)
        val response = model.generateContent(typed)
        val output = response.candidates.firstOrNull()?.response
            ?: error("Gemini Nano returned no structured audit result.")

        val allowedIds = documents.mapTo(hashSetOf()) { it.id }
        return output.findings.mapNotNull { item ->
            if (item.id !in allowedIds) return@mapNotNull null
            val classification = runCatching {
                CoherenceClass.valueOf(item.classification.trim().uppercase())
            }.getOrDefault(CoherenceClass.UNCERTAIN)
            CoherenceFinding(
                id = item.id,
                classification = classification,
                reason = item.reason.trim().take(MAX_REASON_CHARS),
                suggestedGroup = item.suggestedGroup.trim().take(MAX_GROUP_CHARS).ifBlank { null },
            )
        }
    }

    private suspend fun textFallbackAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): List<CoherenceFinding> {
        val aliases = documents.mapIndexed { index, document ->
            "D%04d".format(index + 1) to document
        }
        val aliasToId = aliases.associate { (alias, document) -> alias to document.id }

        val response = model.generateContent(auditRequest(buildFallbackPrompt(scopeLabel, aliases)))
        val output = response.candidates.firstOrNull()?.text
            ?: error("On-device intelligence returned no audit text.")

        return CoherenceTextProtocol.parse(output, aliasToId).map { parsed ->
            CoherenceFinding(
                id = parsed.documentId,
                classification = parsed.classification,
                reason = parsed.reason,
                suggestedGroup = parsed.suggestedGroup,
            )
        }
    }

    private fun auditRequest(prompt: String) =
        generateContentRequest(TextPart(prompt)) {
            temperature = 0.2f
            maxOutputTokens = 1800
            candidateCount = 1
        }

    private fun buildStructuredPrompt(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): String = buildString {
        appendLine("You are performing a read-only folder coherence audit for Pocket Steward.")
        appendLine("Folder/scope: $scopeLabel")
        appendLine("Classify each document relative to the apparent themes of the set.")
        appendLine("Use BELONGS, QUESTIONABLE, DOES_NOT_BELONG, or UNCERTAIN.")
        appendLine("For QUESTIONABLE or DOES_NOT_BELONG, suggest a short destination/group name when useful.")
        appendLine("Do not invent file contents. Preserve every id exactly.")
        appendLine()
        documents.forEach { doc ->
            appendLine("ID: ${doc.id}")
            appendLine("NAME: ${doc.displayName}")
            appendLine("PATH: ${doc.sourcePath}")
            appendLine("EXCERPT:")
            appendLine(doc.excerpt)
            appendLine("---")
        }
    }

    private fun buildFallbackPrompt(
        scopeLabel: String,
        documents: List<Pair<String, SemanticDocument>>,
    ): String = buildString {
        appendLine("You are performing a read-only folder coherence audit for Pocket Steward.")
        appendLine("Folder/scope: $scopeLabel")
        appendLine("Classify every listed document relative to the apparent themes of the set.")
        appendLine("Allowed classifications: BELONGS, QUESTIONABLE, DOES_NOT_BELONG, UNCERTAIN.")
        appendLine("For QUESTIONABLE or DOES_NOT_BELONG, suggest a short group name when useful.")
        appendLine()
        appendLine("OUTPUT PROTOCOL:")
        appendLine("Return exactly one protocol line per document and no prose.")
        appendLine("Each line must be five tab-separated fields:")
        appendLine("PSF<TAB>ALIAS<TAB>CLASSIFICATION<TAB>GROUP<TAB>REASON")
        appendLine("Use - for GROUP when there is no suggested group.")
        appendLine("GROUP and REASON must each stay on one line and contain no tab characters.")
        appendLine("Preserve each ALIAS exactly. Never output a path as the alias.")
        appendLine()
        documents.forEach { (alias, doc) ->
            appendLine("ALIAS: $alias")
            appendLine("NAME: ${doc.displayName}")
            appendLine("PATH: ${doc.sourcePath}")
            appendLine("EXCERPT:")
            appendLine(doc.excerpt)
            appendLine("---")
        }
    }

    private companion object {
        const val MAX_DOCUMENTS = 20
        const val MAX_REASON_CHARS = 400
        const val MAX_GROUP_CHARS = 80
    }
}
