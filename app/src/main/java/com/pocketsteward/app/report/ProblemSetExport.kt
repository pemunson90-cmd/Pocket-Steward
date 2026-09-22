package com.pocketsteward.app.report

import com.pocketsteward.app.data.db.FileRecord

/**
 * Metadata-only bundle for manual review in another tool.
 *
 * No document contents are included. The user can explicitly share this file
 * through Android if they want outside help classifying unresolved items.
 */
object ProblemSetExport {
    fun markdown(scopeLabel: String, records: List<FileRecord>): String = buildString {
        appendLine("# Pocket Steward problem set")
        appendLine()
        appendLine("Scope: ${scopeLabel}")
        appendLine("Unresolved files: ${records.size}")
        appendLine()
        appendLine("These files were left untouched because Pocket Steward's deterministic rules did not classify them confidently.")
        appendLine("This export contains metadata only, not file contents.")
        appendLine()
        records.sortedBy { it.stableRef.lowercase() }.forEach { record ->
            appendLine("## ${record.displayName}")
            appendLine()
            appendLine("- Path: ${record.stableRef}")
            appendLine("- Extension: ${record.extension.ifBlank { "(none)" }}")
            appendLine("- MIME: ${record.mimeType ?: "(unknown)"}")
            appendLine("- Size bytes: ${record.sizeBytes}")
            appendLine("- Modified: ${record.modifiedAt ?: "(unknown)"}")
            appendLine()
        }
    }

    fun json(scopeLabel: String, records: List<FileRecord>): String = buildString {
        append("{\n")
        append("  \"version\": 1,\n")
        append("  \"kind\": \"pocket-steward-problem-set\",\n")
        append("  \"scope\": \"${escape(scopeLabel)}\",\n")
        append("  \"contentsIncluded\": false,\n")
        append("  \"files\": [\n")
        val sorted = records.sortedBy { it.stableRef.lowercase() }
        sorted.forEachIndexed { index, record ->
            append("    {")
            append("\"path\": \"${escape(record.stableRef)}\", ")
            append("\"name\": \"${escape(record.displayName)}\", ")
            append("\"extension\": \"${escape(record.extension)}\", ")
            append("\"mimeType\": ${jsonString(record.mimeType)}, ")
            append("\"sizeBytes\": ${record.sizeBytes}, ")
            append("\"modifiedAt\": ${record.modifiedAt ?: "null"}")
            append("}")
            if (index != sorted.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private fun jsonString(value: String?): String =
        if (value == null) "null" else "\"${escape(value)}\""

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
}
