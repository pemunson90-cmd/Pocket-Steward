package com.pocketsteward.app.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import kotlinx.coroutines.CancellationException
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

    override suspend fun normalizeIntentRequest(request: String): String? {
        if (request.isBlank()) return null
        if (model.checkStatus() != FeatureStatus.AVAILABLE) return null

        val boundedRequest = request.trim().take(MAX_INTENT_INPUT_CHARS)
        val prompt = buildString {
            appendLine("You translate one file-management request into Pocket Steward's bounded command grammar.")
            appendLine("Do not invent filenames, folder names, dates, sizes, search terms, or actions.")
            appendLine("Preserve user literals and numbers exactly when possible.")
            appendLine("Allowed command families:")
            appendLine("- organize [images/documents/APKs/archives/audio/video] [by project] [include subfolders]")
            appendLine("- find [category or filename term] [containing TEXT] [larger/smaller than SIZE] [older/newer than AGE] [largest/smallest/newest/oldest]")
            appendLine("- move FILES to DESTINATION")
            appendLine("- copy FILES to DESTINATION")
            appendLine("- rename OLD to NEW")
            appendLine("- rename files matching TERM to TEMPLATE")
            appendLine("- archive existing archive files")
            appendLine("- find duplicates")
            appendLine("Return exactly one line: PSI|CANONICAL_COMMAND")
            appendLine("If the request cannot be represented without inventing anything, return PSI|UNSUPPORTED")
            appendLine()
            appendLine("USER REQUEST:")
            appendLine(boundedRequest)
        }

        val response = try {
            model.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.1f
                    maxOutputTokens = 220
                    candidateCount = 1
                },
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return null
        }

        return IntentNormalizationProtocol.parse(
            response.candidates.firstOrNull()?.text.orEmpty(),
        )
    }

    override suspend fun coherenceAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): CoherenceAuditResult {
        require(documents.isNotEmpty()) { "A coherence audit needs at least one readable document." }
        check(model.checkStatus() == FeatureStatus.AVAILABLE) { "Gemini Nano is not available yet." }

        val bounded = documents.take(MAX_DOCUMENTS)
        val structuredAvailable = try {
            model.isStructuredOutputFeatureAvailable()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            false
        }

        val attempt = if (structuredAvailable) {
            try {
                structuredAudit(scopeLabel, bounded)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Structured Output is an optimization, not a requirement.
                // A device can advertise or partially expose it and still fail
                // the typed inference path. Ordinary Prompt API remains the
                // compatibility floor.
                textFallbackAudit(scopeLabel, bounded)
            }
        } else {
            textFallbackAudit(scopeLabel, bounded)
        }

        return CoherenceAuditResult(
            findings = attempt.findings,
            modelName = try {
                model.getBaseModelName()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            },
            limited = documents.size > attempt.documentsUsed,
        )
    }

    private suspend fun structuredAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): AuditAttempt {
        val prepared = fitToInputBudget(scopeLabel, documents, structured = true)
        val aliases = alias(prepared)
        val prompt = buildStructuredPrompt(scopeLabel, aliases)
        val typed = generateTypedContentRequest(auditRequest(prompt), CoherenceAuditOutput::class)
        val response = model.generateContent(typed)
        val output = response.candidates.firstOrNull()?.response
            ?: error("Gemini Nano returned no structured audit result.")

        val aliasToId = aliases.associate { (alias, document) -> alias to document.id }
        val findings = output.findings.mapNotNull { item ->
            val documentId = aliasToId[item.id] ?: return@mapNotNull null
            val classification = runCatching {
                CoherenceClass.valueOf(item.classification.trim().uppercase())
            }.getOrDefault(CoherenceClass.UNCERTAIN)
            CoherenceFinding(
                id = documentId,
                classification = classification,
                reason = item.reason.trim().take(MAX_REASON_CHARS),
                suggestedGroup = item.suggestedGroup.trim().take(MAX_GROUP_CHARS).ifBlank { null },
            )
        }
        return AuditAttempt(findings, prepared.size)
    }

    private suspend fun textFallbackAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): AuditAttempt {
        val prepared = fitToInputBudget(scopeLabel, documents, structured = false)

        val first = try {
            runTextFallback(scopeLabel, prepared)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            if (!CoherencePromptPolicy.isComputeFailure(failure.message)) throw failure
            null
        }

        if (first != null && first.findings.isNotEmpty()) {
            return first
        }

        // Retry once for either the observed AICore compute failure or a
        // syntactically successful response that contained no safe protocol
        // records. The retry is deliberately smaller and still read-only.
        val retryDocuments = CoherencePromptPolicy.retry(prepared)
        val retry = try {
            runTextFallback(scopeLabel, retryDocuments)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "On-device intelligence could not complete this audit after Pocket Steward reduced the request. " +
                    "No files were changed.",
                failure,
            )
        }

        if (retry.findings.isNotEmpty()) {
            return retry
        }

        // Some Nano builds intermittently ignore batch-format instructions
        // even though inference itself succeeds. Fall back to tiny one-file
        // prompts instead of making the user rerun the entire audit.
        val individual = individualFallbackAudit(scopeLabel, retryDocuments)
        if (individual.findings.isEmpty()) {
            throw IllegalStateException(
                "On-device intelligence answered, but Pocket Steward could not safely parse any classifications. " +
                    "No files were changed.",
            )
        }
        return individual
    }

    private suspend fun individualFallbackAudit(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): AuditAttempt {
        val findings = mutableListOf<CoherenceFinding>()

        for (document in documents) {
            val prompt = buildString {
                appendLine("Pocket Steward is doing a read-only coherence audit.")
                appendLine("Folder/scope: $scopeLabel")
                appendLine("Classify this one document relative to the folder theme.")
                appendLine("Allowed: BELONGS, QUESTIONABLE, DOES_NOT_BELONG, UNCERTAIN.")
                appendLine("Return exactly CLASSIFICATION|GROUP|REASON.")
                appendLine("Use - for GROUP if none. Keep REASON under 18 words.")
                appendLine("NAME: ${document.displayName}")
                appendLine("EXCERPT:")
                appendLine(document.excerpt)
            }

            val parsed = try {
                val response = model.generateContent(auditRequest(prompt))
                val output = response.candidates.firstOrNull()?.text.orEmpty()
                CoherenceTextProtocol.parseSingle(output, document.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            }

            parsed?.let {
                findings += CoherenceFinding(
                    id = it.documentId,
                    classification = it.classification,
                    reason = it.reason,
                    suggestedGroup = it.suggestedGroup,
                )
            }
        }

        return AuditAttempt(findings, documents.size)
    }

    private suspend fun runTextFallback(
        scopeLabel: String,
        documents: List<SemanticDocument>,
    ): AuditAttempt {
        val aliases = alias(documents)
        val aliasToId = aliases.associate { (alias, document) -> alias to document.id }

        val response = model.generateContent(auditRequest(buildFallbackPrompt(scopeLabel, aliases)))
        val output = response.candidates.firstOrNull()?.text
            ?: error("On-device intelligence returned no audit text.")

        val findings = CoherenceTextProtocol.parse(output, aliasToId).map { parsed ->
            CoherenceFinding(
                id = parsed.documentId,
                classification = parsed.classification,
                reason = parsed.reason,
                suggestedGroup = parsed.suggestedGroup,
            )
        }
        return AuditAttempt(findings, documents.size)
    }

    /**
     * The Prompt API requires input below the model's token limit. The old
     * implementation could hand 20 x 1,800-character excerpts to Nano in one
     * request, which can greatly exceed that limit on real folders.
     *
     * Count the exact request, including Structured Output schema overhead,
     * and progressively compact excerpts/documents until there is headroom.
     * If token counting itself is unavailable, use a deliberately conservative
     * fallback window rather than guessing with the original large request.
     */
    private suspend fun fitToInputBudget(
        scopeLabel: String,
        documents: List<SemanticDocument>,
        structured: Boolean,
    ): List<SemanticDocument> {
        val tokenLimit = try {
            model.getTokenLimit()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            DEFAULT_INPUT_TOKEN_LIMIT
        }
        val budget = minOf(
            SAFE_INPUT_TOKEN_BUDGET,
            (tokenLimit - INPUT_TOKEN_RESERVE).coerceAtLeast(MIN_INPUT_TOKEN_BUDGET),
        )

        var window = CoherencePromptPolicy.initialWindow()
        repeat(MAX_BUDGET_PASSES) {
            val candidate = CoherencePromptPolicy.compact(documents, window)
            val aliases = alias(candidate)
            val tokenCount = try {
                if (structured) {
                    val typed = generateTypedContentRequest(
                        auditRequest(buildStructuredPrompt(scopeLabel, aliases)),
                        CoherenceAuditOutput::class,
                    )
                    model.countTokens(typed).totalTokens
                } else {
                    model.countTokens(
                        auditRequest(buildFallbackPrompt(scopeLabel, aliases)),
                    ).totalTokens
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                return CoherencePromptPolicy.conservative(documents)
            }

            if (tokenCount <= budget) return candidate
            window = CoherencePromptPolicy.next(window) ?: return candidate
        }

        return CoherencePromptPolicy.conservative(documents)
    }

    private fun alias(documents: List<SemanticDocument>): List<Pair<String, SemanticDocument>> =
        documents.mapIndexed { index, document ->
            "D%04d".format(index + 1) to document
        }

    private fun auditRequest(prompt: String) =
        generateContentRequest(TextPart(prompt)) {
            temperature = 0.2f
            maxOutputTokens = 1200
            candidateCount = 1
        }

    private fun buildStructuredPrompt(
        scopeLabel: String,
        documents: List<Pair<String, SemanticDocument>>,
    ): String = buildString {
        appendLine("You are performing a read-only folder coherence audit for Pocket Steward.")
        appendLine("Folder/scope: $scopeLabel")
        appendLine("Classify each document relative to the apparent themes of the set.")
        appendLine("Use BELONGS, QUESTIONABLE, DOES_NOT_BELONG, or UNCERTAIN.")
        appendLine("For QUESTIONABLE or DOES_NOT_BELONG, suggest a short destination/group name when useful.")
        appendLine("Keep each reason under 18 words and each group name under 6 words.")
        appendLine("Do not invent file contents. Preserve every alias exactly in the id field.")
        appendLine()
        documents.forEach { (alias, doc) ->
            appendLine("ID: $alias")
            appendLine("NAME: ${doc.displayName}")
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
        appendLine("Keep REASON under 18 words and GROUP under 6 words.")
        appendLine()
        appendLine("OUTPUT PROTOCOL:")
        appendLine("Return exactly one protocol line per document and no prose.")
        appendLine("Each line must be exactly five pipe-separated fields:")
        appendLine("PSF|ALIAS|CLASSIFICATION|GROUP|REASON")
        appendLine("Do not make a Markdown table and do not add bullets or numbering.")
        appendLine("Use - for GROUP when there is no suggested group.")
        appendLine("GROUP and REASON must each stay on one line and contain no pipe characters.")
        appendLine("Preserve each ALIAS exactly. Never output a path as the alias.")
        appendLine()
        documents.forEach { (alias, doc) ->
            appendLine("ALIAS: $alias")
            appendLine("NAME: ${doc.displayName}")
            appendLine("EXCERPT:")
            appendLine(doc.excerpt)
            appendLine("---")
        }
    }

    private data class AuditAttempt(
        val findings: List<CoherenceFinding>,
        val documentsUsed: Int,
    )

    private companion object {
        const val MAX_DOCUMENTS = 20
        const val MAX_INTENT_INPUT_CHARS = 1_200
        const val MAX_REASON_CHARS = 400
        const val MAX_GROUP_CHARS = 80
        const val DEFAULT_INPUT_TOKEN_LIMIT = 4000
        const val SAFE_INPUT_TOKEN_BUDGET = 3400
        const val INPUT_TOKEN_RESERVE = 512
        const val MIN_INPUT_TOKEN_BUDGET = 2200
        const val MAX_BUDGET_PASSES = 16
    }
}

/**
 * Pure request-shaping policy so the hardware-sensitive prompt size rules are
 * unit-testable without AICore.
 */
internal object CoherencePromptPolicy {
    data class Window(
        val maxDocuments: Int,
        val excerptChars: Int,
    )

    private const val MAX_DOCUMENTS = 20
    private const val INITIAL_EXCERPT_CHARS = 700
    private const val MIN_EXCERPT_CHARS = 240
    private const val MIN_DOCUMENTS = 4
    private const val CONSERVATIVE_DOCUMENTS = 8
    private const val CONSERVATIVE_EXCERPT_CHARS = 400
    private const val RETRY_DOCUMENTS = 6
    private const val RETRY_EXCERPT_CHARS = 320

    fun initialWindow(): Window = Window(MAX_DOCUMENTS, INITIAL_EXCERPT_CHARS)

    fun compact(
        documents: List<SemanticDocument>,
        window: Window,
    ): List<SemanticDocument> =
        documents.take(window.maxDocuments).map { document ->
            document.copy(excerpt = document.excerpt.take(window.excerptChars))
        }

    fun next(window: Window): Window? {
        if (window.excerptChars > MIN_EXCERPT_CHARS) {
            val nextChars = maxOf(MIN_EXCERPT_CHARS, window.excerptChars * 3 / 4)
            if (nextChars != window.excerptChars) return window.copy(excerptChars = nextChars)
        }
        if (window.maxDocuments > MIN_DOCUMENTS) {
            return window.copy(maxDocuments = maxOf(MIN_DOCUMENTS, window.maxDocuments - 2))
        }
        return null
    }

    fun conservative(documents: List<SemanticDocument>): List<SemanticDocument> =
        compact(documents, Window(CONSERVATIVE_DOCUMENTS, CONSERVATIVE_EXCERPT_CHARS))

    fun retry(documents: List<SemanticDocument>): List<SemanticDocument> =
        compact(documents, Window(RETRY_DOCUMENTS, RETRY_EXCERPT_CHARS))

    fun isComputeFailure(message: String?): Boolean {
        val normalized = message.orEmpty().uppercase()
        return "COMPUTE_ERROR" in normalized ||
            ("INFERENCE_ERROR" in normalized && "INFERENCE FAILED" in normalized)
    }
}
