package com.pocketsteward.app.content.index

import com.pocketsteward.app.data.db.FileRecord

object ContentIndexPolicy {
    const val EXTRACTOR_VERSION: Int = 2

    fun canReuse(existing: IndexedDocument?, record: FileRecord, requestedProfile: String = "FULL"): Boolean {
        if (existing == null) return false
        if (existing.extractionProfile !in setOf("FULL", "FILING") || requestedProfile !in setOf("FULL", "FILING")) return false
        if (requestedProfile == "FULL" && existing.extractionProfile != "FULL") return false
        if (existing.extractorVersion != EXTRACTOR_VERSION) return false
        if (record.modifiedAt == null) return false
        if (record.quickFingerprint?.startsWith(com.pocketsteward.app.evidence.EvidenceFingerprint.PREFIX) != true) return false
        if (existing.quickFingerprint != record.quickFingerprint) return false
        if (existing.sizeBytes != record.sizeBytes) return false
        if (existing.modifiedAt != record.modifiedAt) return false
        if (!existing.extension.equals(record.extension, ignoreCase = true)) return false
        if (existing.extractionStatus == IndexedExtractionStatus.FAILED.name) return false
        return true
    }
}

/**
 * Builds a conservative FTS4 MATCH expression from ordinary user text.
 *
 * Every token is double-quoted and embedded quotes are doubled. Operators in
 * the user's text therefore become literal token content rather than FTS
 * syntax.
 */
object ContentFtsQuery {
    fun build(raw: String): String {
        val tokens = raw
            .trim()
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(16)

        require(tokens.isNotEmpty()) { "Search query cannot be blank." }

        return tokens.joinToString(" AND ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\""
        }
    }
}
