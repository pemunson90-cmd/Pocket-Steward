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
    val sourceFolder: String? = null,
    val projectHomePath: String? = null,
)

enum class ProjectHierarchyStrategy {
    FLAT,
    VERSIONED,
    PROJECT_ROLES,
    CATEGORY,
}

data class ProjectHome(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val aliases: List<String> = emptyList(),
    val packageIds: List<String> = emptyList(),
    val hierarchy: ProjectHierarchyStrategy = ProjectHierarchyStrategy.VERSIONED,
    val roleFolders: Map<String, String> = emptyMap(),
)

data class InboxRoot(
    val path: String,
    val name: String,
)

/** Provider document IDs are opaque and case-sensitive; direct paths retain existing collision policy. */
fun organizationReferenceIdentity(value: String): String {
    val ref = value.trim().trimEnd('/')
    return if (ref.startsWith("content://") || ref.startsWith("ps-child:")) ref else ref.lowercase(java.util.Locale.ROOT)
}

object OrganizationPreferenceCodec {
    private const val LIST_SEPARATOR = '\u001F'

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
            listOf(enc(value.term), enc(value.destinationFolder), enc(value.sourceFolder.orEmpty()), enc(value.projectHomePath.orEmpty())).joinToString(";")
        }

    fun decodeCorrections(raw: String?): List<CorrectionRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size !in setOf(2, 4)) return@mapNotNull null
            runCatching { CorrectionRule(dec(parts[0]), dec(parts[1]),
                parts.getOrNull(2)?.let(::dec)?.takeIf { it.isNotBlank() },
                parts.getOrNull(3)?.let(::dec)?.takeIf { it.isNotBlank() }) }.getOrNull()
        }.filter { it.term.isNotBlank() && it.destinationFolder.isNotBlank() }
            .distinctBy(CorrectionRulePolicy::identity)
            .toList()
    }

    fun encodeProjectHomes(values: List<ProjectHome>): String =
        values.joinToString("\n") { value ->
            listOf(
                enc(value.id),
                enc(value.name),
                enc(value.path),
                enc(value.aliases.joinToString(LIST_SEPARATOR.toString())),
                enc(value.packageIds.joinToString(LIST_SEPARATOR.toString())),
                enc(value.hierarchy.name),
                enc(HierarchyTemplate(value.roleFolders).encode()),
            ).joinToString(";")
        }

    fun decodeProjectHomes(raw: String?): List<ProjectHome> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';', limit = 7)
            if (parts.size !in 6..7) return@mapNotNull null
            runCatching {
                ProjectHome(
                    id = dec(parts[0]),
                    name = dec(parts[1]),
                    path = dec(parts[2]),
                    aliases = splitList(dec(parts[3])),
                    packageIds = splitList(dec(parts[4])),
                    hierarchy = ProjectHierarchyStrategy.entries.firstOrNull {
                        it.name == dec(parts[5])
                    } ?: ProjectHierarchyStrategy.VERSIONED,
                    roleFolders = if (parts.size == 7) HierarchyTemplate.parse(dec(parts[6])).roleFolders else emptyMap(),
                )
            }.getOrNull()
        }.filter { it.name.isNotBlank() && it.path.isNotBlank() }
            .distinctBy { organizationReferenceIdentity(it.path) }
            .toList()
    }

    fun encodeInboxRoots(values: List<InboxRoot>): String =
        values.joinToString("\n") { value ->
            listOf(enc(value.path), enc(value.name)).joinToString(";")
        }

    fun decodeInboxRoots(raw: String?): List<InboxRoot> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(';', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            runCatching { InboxRoot(dec(parts[0]), dec(parts[1])) }.getOrNull()
        }.filter { it.path.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { organizationReferenceIdentity(it.path) }
            .toList()
    }

    private fun splitList(value: String): List<String> =
        value.split(LIST_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
