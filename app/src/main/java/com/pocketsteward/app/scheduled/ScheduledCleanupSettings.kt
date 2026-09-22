package com.pocketsteward.app.scheduled

data class ScheduledCleanupSettings(
    val enabled: Boolean = false,
    val intervalHours: Long = 24,
    val roots: List<String> = emptyList(),
)

object ScheduledCleanupCodec {
    fun encode(value: ScheduledCleanupSettings): String = buildString {
        append(if (value.enabled) "1" else "0")
        append('|')
        append(value.intervalHours.coerceIn(1, 24 * 30))
        append('|')
        append(value.roots.joinToString("\t") { it.replace("\t", "").replace("\n", "") })
    }

    fun decode(raw: String?): ScheduledCleanupSettings {
        if (raw.isNullOrBlank()) return ScheduledCleanupSettings()
        val parts = raw.split('|', limit = 3)
        return ScheduledCleanupSettings(
            enabled = parts.getOrNull(0) == "1",
            intervalHours = parts.getOrNull(1)?.toLongOrNull()?.coerceIn(1, 24 * 30) ?: 24,
            roots = parts.getOrNull(2)
                .orEmpty()
                .split('\t')
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotBlank() }
                .distinct(),
        )
    }
}


data class PendingCleanupSuggestion(
    val createdAtEpochMs: Long,
    val roots: List<String>,
    val newFileCount: Int,
    val obviousMatchCount: Int,
)

object PendingCleanupSuggestionCodec {
    fun encode(value: PendingCleanupSuggestion): String {
        val roots = value.roots
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",") { root ->
                java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(root.toByteArray(Charsets.UTF_8))
            }
        return listOf(
            "1",
            value.createdAtEpochMs.toString(),
            value.newFileCount.coerceAtLeast(0).toString(),
            value.obviousMatchCount.coerceAtLeast(0).toString(),
            roots,
        ).joinToString("|")
    }

    fun decode(raw: String?): PendingCleanupSuggestion? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|', limit = 5)
        if (parts.size != 5 || parts[0] != "1") return null
        val createdAt = parts[1].toLongOrNull() ?: return null
        val newFiles = parts[2].toIntOrNull() ?: return null
        val obvious = parts[3].toIntOrNull() ?: return null
        val roots = if (parts[4].isBlank()) {
            emptyList()
        } else {
            parts[4].split(',').mapNotNull { encoded ->
                runCatching {
                    String(
                        java.util.Base64.getUrlDecoder().decode(encoded),
                        Charsets.UTF_8,
                    )
                }.getOrNull()
            }
        }
        if (roots.isEmpty()) return null
        return PendingCleanupSuggestion(
            createdAtEpochMs = createdAt,
            roots = roots,
            newFileCount = newFiles.coerceAtLeast(0),
            obviousMatchCount = obvious.coerceAtLeast(0),
        )
    }
}
