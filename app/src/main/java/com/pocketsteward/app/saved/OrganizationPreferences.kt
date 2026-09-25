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

data class InboxRoot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
)

enum class ProjectHierarchy {
    FLAT,
    VERSIONED,
    CATEGORY,
}

data class ProjectHome(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val aliases: List<String> = emptyList(),
    val packageIds: List<String> = emptyList(),
    val hierarchy: ProjectHierarchy = ProjectHierarchy.VERSIONED,
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

    fun encodeInboxRoots(values: List<InboxRoot>): String =
        values.joinToString("\n") { value ->
            listOf(enc(value.id), enc(value.name), enc(value.path)).joinToString(";")
        }

    fun decodeInboxRoots(raw: String?): List<InboxRoot> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            runCatching {
                InboxRoot(dec(parts[0]), dec(parts[1]), dec(parts[2]))
            }.getOrNull()
        }.filter { it.name.isNotBlank() && it.path.isNotBlank() }
            .distinctBy { it.path.lowercase() }
            .toList()
    }

    fun encodeProjectHomes(values: List<ProjectHome>): String =
        values.joinToString("\n") { value ->
            listOf(
                enc(value.id),
                enc(value.name),
                enc(value.path),
                value.hierarchy.name,
                encodeList(value.aliases),
                encodeList(value.packageIds),
            ).joinToString(";")
        }

    fun decodeProjectHomes(raw: String?): List<ProjectHome> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size !in 3..6) return@mapNotNull null
            runCatching {
                ProjectHome(
                    id = dec(parts[0]),
                    name = dec(parts[1]),
                    path = dec(parts[2]),
                    hierarchy = parts.getOrNull(3)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ProjectHierarchy.valueOf(it) }
                        ?: ProjectHierarchy.VERSIONED,
                    aliases = parts.getOrNull(4)?.let(::decodeList).orEmpty(),
                    packageIds = parts.getOrNull(5)?.let(::decodeList).orEmpty(),
                )
            }.getOrNull()
        }.filter { it.name.isNotBlank() && it.path.isNotBlank() }
            .distinctBy { it.path.lowercase() }
            .toList()
    }

    private fun encodeList(values: List<String>): String =
        values.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(",") { enc(it) }

    private fun decodeList(raw: String): List<String> =
        raw.split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { dec(it) }.getOrNull() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
