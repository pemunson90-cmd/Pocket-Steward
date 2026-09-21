package com.pocketsteward.app.saved

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

data class FavoriteDestination(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
)

data class CorrectionRule(
    val term: String,
    val destinationFolder: String,
)

object OrganizationPreferenceCodec {
    fun encodeDestinations(values: List<FavoriteDestination>): String =
        values.joinToString("\n") { value ->
            listOf(enc(value.id), enc(value.name), enc(value.path)).joinToString(";")
        }

    fun decodeDestinations(raw: String?): List<FavoriteDestination> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            runCatching {
                FavoriteDestination(dec(parts[0]), dec(parts[1]), dec(parts[2]))
            }.getOrNull()
        }.filter { it.name.isNotBlank() && it.path.isNotBlank() }.toList()
    }

    fun encodeCorrections(values: List<CorrectionRule>): String =
        values.joinToString("\n") { value ->
            listOf(enc(value.term), enc(value.destinationFolder)).joinToString(";")
        }

    fun decodeCorrections(raw: String?): List<CorrectionRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            runCatching { CorrectionRule(dec(parts[0]), dec(parts[1])) }.getOrNull()
        }.filter { it.term.isNotBlank() && it.destinationFolder.isNotBlank() }
            .distinctBy { it.term.lowercase() }
            .toList()
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
