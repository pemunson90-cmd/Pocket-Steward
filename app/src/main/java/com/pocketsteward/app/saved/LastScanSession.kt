package com.pocketsteward.app.saved

import com.pocketsteward.app.storage.StorageAccessMode
import java.nio.charset.StandardCharsets
import java.util.Base64

data class LastScanRoot(
    val label: String,
    val rawRef: String,
)

data class LastScanSession(
    val mode: StorageAccessMode,
    val roots: List<LastScanRoot>,
    val savedAtEpochMs: Long,
)

object LastScanSessionCodec {
    fun encode(value: LastScanSession): String = buildString {
        append("1|")
        append(value.mode.name)
        append('|')
        append(value.savedAtEpochMs)
        append('\n')
        value.roots.forEach { root ->
            append(enc(root.label))
            append(';')
            append(enc(root.rawRef))
            append('\n')
        }
    }

    fun decode(raw: String?): LastScanSession? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        val header = lines.first().split('|', limit = 3)
        if (header.size != 3 || header[0] != "1") return null
        val mode = runCatching { StorageAccessMode.valueOf(header[1]) }.getOrNull() ?: return null
        val savedAt = header[2].toLongOrNull() ?: return null
        val roots = lines.drop(1).mapNotNull { line ->
            val parts = line.split(';', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            runCatching {
                LastScanRoot(
                    label = dec(parts[0]),
                    rawRef = dec(parts[1]),
                )
            }.getOrNull()
        }.filter { it.label.isNotBlank() && it.rawRef.isNotBlank() }
        if (roots.isEmpty()) return null
        return LastScanSession(mode, roots, savedAt)
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
