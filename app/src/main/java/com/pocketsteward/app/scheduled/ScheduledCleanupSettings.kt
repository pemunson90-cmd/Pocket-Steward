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
