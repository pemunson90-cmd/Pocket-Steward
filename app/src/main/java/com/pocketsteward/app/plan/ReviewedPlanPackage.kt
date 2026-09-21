package com.pocketsteward.app.plan

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Portable reviewed-plan package. The payload is the same typed DurablePlan
 * codec used for interruption-safe execution, wrapped in human-identifiable
 * JSON so it can be shared/reviewed outside the app and imported back through
 * PlanValidator.
 */
object ReviewedPlanPackage {
    private const val FORMAT = "pocket-steward-reviewed-plan"
    private const val VERSION = 1

    fun encode(goal: String, operations: List<PlannedOperation>): String {
        require(operations.isNotEmpty()) { "Reviewed plan needs at least one operation." }
        val durable = DurablePlanCodec.encode(goal, operations)
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(durable.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            append("{\n")
            append("  \"format\": \"$FORMAT\",\n")
            append("  \"version\": $VERSION,\n")
            append("  \"goal\": \"${escape(goal)}\",\n")
            append("  \"durablePlanBase64\": \"$payload\"\n")
            append("}\n")
        }
    }

    fun decodeOrNull(json: String): DurablePlan? = runCatching {
        require(Regex("""\"format\"\s*:\s*\"$FORMAT\"""").containsMatchIn(json))
        require(Regex("""\"version\"\s*:\s*$VERSION\b""").containsMatchIn(json))
        val payload = Regex("""\"durablePlanBase64\"\s*:\s*\"([A-Za-z0-9_-]+)\"""")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val durable = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
        DurablePlanCodec.decodeOrNull(durable)
    }.getOrNull()

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
