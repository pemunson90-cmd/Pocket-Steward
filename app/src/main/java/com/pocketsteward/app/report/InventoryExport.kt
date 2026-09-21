package com.pocketsteward.app.report

import com.pocketsteward.app.data.db.FileRecord

object InventoryExport {
    fun json(scopeLabel: String, records: List<FileRecord>): String = buildString {
        append("{\n")
        append("  \"version\": 1,\n")
        append("  \"scope\": \"${escape(scopeLabel)}\",\n")
        append("  \"files\": [\n")
        records.filterNot { it.isDirectory }.forEachIndexed { index, record ->
            append("    {")
            append("\"path\": \"${escape(record.stableRef)}\", ")
            append("\"name\": \"${escape(record.displayName)}\", ")
            append("\"extension\": \"${escape(record.extension)}\", ")
            append("\"sizeBytes\": ${record.sizeBytes}, ")
            append("\"modifiedAt\": ${record.modifiedAt ?: "null"}, ")
            append("\"mimeType\": ${jsonString(record.mimeType)}, ")
            append("\"width\": ${record.width ?: "null"}, ")
            append("\"height\": ${record.height ?: "null"}, ")
            append("\"durationMs\": ${record.durationMs ?: "null"}, ")
            append("\"apkPackageName\": ${jsonString(record.apkPackageName)}, ")
            append("\"apkVersionName\": ${jsonString(record.apkVersionName)}")
            append("}")
            if (index != records.filterNot { it.isDirectory }.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n")
        append("}\n")
    }

    fun csv(records: List<FileRecord>): String = buildString {
        appendLine("path,name,extension,sizeBytes,modifiedAt,mimeType,width,height,durationMs,apkPackageName,apkVersionName")
        records.filterNot { it.isDirectory }.forEach { record ->
            appendLine(
                listOf(
                    record.stableRef,
                    record.displayName,
                    record.extension,
                    record.sizeBytes.toString(),
                    record.modifiedAt?.toString().orEmpty(),
                    record.mimeType.orEmpty(),
                    record.width?.toString().orEmpty(),
                    record.height?.toString().orEmpty(),
                    record.durationMs?.toString().orEmpty(),
                    record.apkPackageName.orEmpty(),
                    record.apkVersionName.orEmpty(),
                ).joinToString(",") { csvCell(it) },
            )
        }
    }

    private fun csvCell(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

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
                } else append(ch)
            }
        }
    }
}
