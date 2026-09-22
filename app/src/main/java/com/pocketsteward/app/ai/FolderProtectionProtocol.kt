package com.pocketsteward.app.ai

/**
 * Strict one-line protocol for Nano folder-protection suggestions.
 *
 * The model sees aliases instead of storage paths, so a parsed response can
 * only refer back to the candidate set supplied by Pocket Steward.
 */
internal object FolderProtectionProtocol {
    fun parse(
        text: String,
        aliasToId: Map<String, String>,
    ): List<FolderProtectionSuggestion> =
        text.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("PSP|") }
            .mapNotNull { line ->
                val fields = line.split('|', limit = 4)
                if (fields.size != 4) return@mapNotNull null
                val id = aliasToId[fields[1]] ?: return@mapNotNull null
                val protect = when (fields[2].trim().uppercase()) {
                    "YES", "PROTECT" -> true
                    "NO", "LEAVE" -> false
                    else -> return@mapNotNull null
                }
                val reason = fields[3]
                    .trim()
                    .replace(Regex("""\s+"""), " ")
                    .take(MAX_REASON_CHARS)
                if (reason.isBlank()) return@mapNotNull null
                FolderProtectionSuggestion(id, protect, reason)
            }
            .distinctBy(FolderProtectionSuggestion::id)
            .toList()

    private const val MAX_REASON_CHARS = 240
}
