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
                val line = rawLine.trimEnd()
                if (!line.startsWith("$PREFIX\t")) return@forEach

                val fields = line.split('\t', limit = 5)
                if (fields.size != 5) return@forEach

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
}
