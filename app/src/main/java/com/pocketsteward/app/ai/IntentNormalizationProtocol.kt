package com.pocketsteward.app.ai

/**
 * Tiny fail-closed protocol around Nano's language normalization.
 *
 * The returned text is not an operation plan. It is fed back through the
 * deterministic intent parser, so malformed or imaginative model output has
 * no filesystem authority.
 */
object IntentNormalizationProtocol {
    private const val PREFIX = "PSI|"
    private const val MAX_COMMAND_CHARS = 600

    fun parse(output: String): String? {
        val protocolLines = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PREFIX) }
            .take(2)
            .toList()

        if (protocolLines.size != 1) return null
        val payload = protocolLines.single()
            .removePrefix(PREFIX)
            .trim()
            .take(MAX_COMMAND_CHARS)

        if (payload.isBlank() || payload.equals("UNSUPPORTED", ignoreCase = true)) return null
        if ('\n' in payload || '\r' in payload) return null
        return payload
    }
}
