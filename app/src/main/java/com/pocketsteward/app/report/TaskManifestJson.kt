package com.pocketsteward.app.report

object TaskManifestJson {
    fun render(document: TaskManifestDocument): String = buildString {
        append("{\n")
        append("  \"version\": 1,\n")
        append("  \"taskRunId\": ${document.taskRunId},\n")
        append("  \"title\": \"${escape(document.title)}\",\n")
        append("  \"assertion\": {")
        append("\"groups\": ${document.assertion.groups}, ")
        append("\"kept\": ${document.assertion.kept}, ")
        append("\"trashed\": ${document.assertion.trashed}, ")
        append("\"consistent\": ${document.assertion.consistent}")
        append("},\n")
        append("  \"entries\": [\n")
        document.entries.forEachIndexed { index, entry ->
            append("    {")
            append("\"sequence\": ${entry.sequence}, ")
            append("\"operation\": \"${escape(entry.operation)}\", ")
            append("\"originalPath\": \"${escape(entry.originalPath)}\", ")
            append("\"resultPath\": ${jsonString(entry.resultPath)}, ")
            append("\"fingerprint\": ${jsonString(entry.fingerprint)}, ")
            append("\"succeeded\": ${entry.succeeded}, ")
            append("\"error\": ${jsonString(entry.error)}, ")
            append("\"reason\": ${jsonString(entry.reason)}")
            append("}")
            if (index != document.entries.lastIndex) append(",")
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
