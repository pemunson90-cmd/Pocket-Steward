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
    /**
     * Exact refs discovered by the scheduled scan. A review should propose
     * changes for these files, not unexpectedly revisit every older file in
     * the watched roots. Bounded before persistence by the worker.
     */
    val newFileRefs: List<String> = emptyList(),
)

object PendingCleanupSuggestionCodec {
    private const val VERSION_1 = "1"
    private const val VERSION_2 = "2"

    fun encode(value: PendingCleanupSuggestion): String {
        val roots = encodeList(value.roots)
        val refs = encodeList(value.newFileRefs)
        return listOf(
            VERSION_2,
            value.createdAtEpochMs.toString(),
            value.newFileCount.coerceAtLeast(0).toString(),
            value.obviousMatchCount.coerceAtLeast(0).toString(),
            roots,
            refs,
        ).joinToString("|")
    }

    fun decode(raw: String?): PendingCleanupSuggestion? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size < 5) return null

        return when (parts[0]) {
            VERSION_1 -> decodeV1(parts)
            VERSION_2 -> decodeV2(parts)
            else -> null
        }
    }

    private fun decodeV1(parts: List<String>): PendingCleanupSuggestion? {
        if (parts.size != 5) return null
        val core = core(parts) ?: return null
        return PendingCleanupSuggestion(
            createdAtEpochMs = core.createdAt,
            roots = core.roots,
            newFileCount = core.newFiles,
            obviousMatchCount = core.obvious,
            newFileRefs = emptyList(),
        )
    }

    private fun decodeV2(parts: List<String>): PendingCleanupSuggestion? {
        if (parts.size != 6) return null
        val core = core(parts) ?: return null
        return PendingCleanupSuggestion(
            createdAtEpochMs = core.createdAt,
            roots = core.roots,
            newFileCount = core.newFiles,
            obviousMatchCount = core.obvious,
            newFileRefs = decodeList(parts[5]),
        )
    }

    private data class Core(
        val createdAt: Long,
        val newFiles: Int,
        val obvious: Int,
        val roots: List<String>,
    )

    private fun core(parts: List<String>): Core? {
        val createdAt = parts[1].toLongOrNull() ?: return null
        val newFiles = parts[2].toIntOrNull() ?: return null
        val obvious = parts[3].toIntOrNull() ?: return null
        val roots = decodeList(parts[4])
        if (roots.isEmpty()) return null
        return Core(
            createdAt = createdAt,
            newFiles = newFiles.coerceAtLeast(0),
            obvious = obvious.coerceAtLeast(0),
            roots = roots,
        )
    }

    private fun encodeList(values: List<String>): String =
        values
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",") { root ->
                java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(root.toByteArray(Charsets.UTF_8))
            }

    private fun decodeList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { encoded ->
            runCatching {
                String(
                    java.util.Base64.getUrlDecoder().decode(encoded),
                    Charsets.UTF_8,
                )
            }.getOrNull()
        }.filter { it.isNotBlank() }.distinct()
    }
}
