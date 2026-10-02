package com.pocketsteward.app.saved

import java.nio.charset.StandardCharsets
import java.util.Base64

enum class WorkflowKind(val label: String) { REQUEST("Saved request"), INBOX_FILING("Organize inbox"), UNCERTAIN_FILING("Sort Uncertain") }

data class SavedWorkflow(
    val id: String,
    val name: String,
    val request: String,
    val roots: List<String>,
    val kind: WorkflowKind = WorkflowKind.REQUEST,
    val preferences: WorkflowPreferences = WorkflowPreferences(),
)

object SavedWorkflowCodec {
    fun encode(workflows: List<SavedWorkflow>): String =
        workflows.joinToString("\n") { workflow ->
            listOf(
                enc(workflow.id),
                enc(workflow.name),
                enc(workflow.request),
                workflow.roots.joinToString(",") { enc(it) },
                workflow.kind.name,
                enc(WorkflowPreferencesCodec.encode(workflow.preferences)),
            ).joinToString(";")
        }

    fun decode(raw: String?): List<SavedWorkflow> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            if (line.length > 100_000) return@mapNotNull null
            val parts = line.split(';')
            if (parts.size !in 4..6) return@mapNotNull null
            runCatching {
                SavedWorkflow(
                    kind = if (parts.size >= 5) WorkflowKind.valueOf(parts[4]) else WorkflowKind.REQUEST,
                    preferences = if (parts.size == 6) WorkflowPreferencesCodec.decode(dec(parts[5])) else WorkflowPreferences(),
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
