package com.pocketsteward.app.saved

import java.nio.charset.StandardCharsets
import java.util.Base64

data class SavedWorkflow(
    val id: String,
    val name: String,
    val request: String,
    val roots: List<String>,
)

object SavedWorkflowCodec {
    fun encode(workflows: List<SavedWorkflow>): String =
        workflows.joinToString("\n") { workflow ->
            listOf(
                enc(workflow.id),
                enc(workflow.name),
                enc(workflow.request),
                workflow.roots.joinToString(",") { enc(it) },
            ).joinToString(";")
        }

    fun decode(raw: String?): List<SavedWorkflow> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';', limit = 4)
            if (parts.size != 4) return@mapNotNull null
            runCatching {
                SavedWorkflow(
                    id = dec(parts[0]),
                    name = dec(parts[1]),
                    request = dec(parts[2]),
                    roots = parts[3]
                        .split(',')
                        .filter { it.isNotBlank() }
                        .map(::dec)
                        .map { it.trimEnd('/') }
                        .filter { it.isNotBlank() }
                        .distinct(),
                )
            }.getOrNull()?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() && it.roots.isNotEmpty() }
        }.toList()
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
