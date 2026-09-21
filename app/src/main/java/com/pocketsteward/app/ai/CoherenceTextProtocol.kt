package com.pocketsteward.app.ai

/**
 * Fail-closed parser for the ordinary Prompt API fallback used when ML Kit
 * Structured Output is unavailable on a device/model configuration.
 *
 * The model never sees filesystem authority here. It emits short local
 * aliases (D0001, D0002...) which callers map back to exact known document
 * IDs only after parsing.
 */
object CoherenceTextProtocol {
    const val PREFIX = "PSF"
    private const val MAX_REASON_CHARS = 400
    private const val MAX_GROUP_CHARS = 80
    private const val MAX_PROTOCOL_LINES = 256

    data class ParsedFinding(
        val documentId: String,
        val classification: CoherenceClass,
        val suggestedGroup: String?,
        val reason: String,
    )

    fun parse(
        output: String,
        aliasToDocumentId: Map<String, String>,
    ): List<ParsedFinding> {
        if (aliasToDocumentId.isEmpty()) return emptyList()

        val parsedByAlias = linkedMapOf<String, MutableList<ParsedFinding>>()

        output.lineSequence()
            .take(MAX_PROTOCOL_LINES)
            .forEach { rawLine ->
                val fields = protocolFields(rawLine) ?: return@forEach
                val alias = fields[1].trim()
                val documentId = aliasToDocumentId[alias] ?: return@forEach

                val classification = runCatching {
                    CoherenceClass.valueOf(fields[2].trim().uppercase())
                }.getOrDefault(CoherenceClass.UNCERTAIN)

                val group = fields[3]
                    .trim()
                    .take(MAX_GROUP_CHARS)
                    .takeUnless { it.isBlank() || it == "-" }

                val reason = fields[4]
                    .trim()
                    .take(MAX_REASON_CHARS)

                parsedByAlias.getOrPut(alias) { mutableListOf() } += ParsedFinding(
                    documentId = documentId,
                    classification = classification,
                    suggestedGroup = group,
                    reason = reason,
                )
            }

        // Conflicting or repeated model records are not reconciled by guess.
        // Discard the alias entirely and let the audit omit that finding.
        return parsedByAlias.values
            .filter { it.size == 1 }
            .map { it.single() }
    }


    /**
     * Last-resort parser for a single-document prompt. With only one known
     * document there is no alias ambiguity: we still require one of the
     * closed classification enum values, but tolerate harmless prose or
     * Markdown formatting around it.
     */
    fun parseSingle(
        output: String,
        documentId: String,
    ): ParsedFinding? {
        output.lineSequence()
            .take(32)
            .map { it.trim().trim('|', '*', '-', ' ', '#', '>') }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val pipe = line.split('|', limit = 3).map { it.trim() }
                if (pipe.size >= 1) {
                    parseClassification(pipe[0])?.let { classification ->
                        val group = pipe.getOrNull(1)
                            ?.take(MAX_GROUP_CHARS)
                            ?.takeUnless { it.isBlank() || it == "-" }
                        val reason = pipe.getOrNull(2).orEmpty().take(MAX_REASON_CHARS)
                        return ParsedFinding(documentId, classification, group, reason)
                    }
                }

                val normalized = line.uppercase().replace(' ', '_')
                CoherenceClass.entries.firstOrNull { classification ->
                    Regex("""(^|[^A-Z_])${classification.name}([^A-Z_]|$)""")
                        .containsMatchIn(normalized)
                }?.let { classification ->
                    return ParsedFinding(
                        documentId = documentId,
                        classification = classification,
                        suggestedGroup = null,
                        reason = line.take(MAX_REASON_CHARS),
                    )
                }
            }
        return null
    }

    private fun parseClassification(raw: String): CoherenceClass? {
        val normalized = raw
            .trim()
            .uppercase()
            .replace(' ', '_')
            .replace('-', '_')
        return CoherenceClass.entries.firstOrNull { it.name == normalized }
    }

    /**
     * Nano can preserve the requested record while normalizing literal tabs
     * into printable pipes, especially when it formats the answer as Markdown.
     * Both forms carry the same five typed fields, so accepting them remains
     * deterministic rather than heuristic.
     *
     * Markdown table edge pipes are stripped first. Unknown prose, wrong field
     * counts, unknown aliases, and conflicting duplicate records still fail
     * closed.
     */
    private fun protocolFields(rawLine: String): List<String>? {
        var line = rawLine.trim()
        if (line.isBlank()) return null

        if (line.startsWith("|") && line.endsWith("|") && line.length > 1) {
            line = line.substring(1, line.length - 1).trim()
        }

        val tabFields = line.split('\t', limit = 5)
        if (tabFields.size == 5 && tabFields[0].trim() == PREFIX) {
            return tabFields
        }

        val pipeFields = line.split('|', limit = 5)
        if (pipeFields.size == 5 && pipeFields[0].trim() == PREFIX) {
            return pipeFields
        }

        return null
    }
}
