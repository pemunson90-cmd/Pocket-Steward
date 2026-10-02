package com.pocketsteward.app.projects

import com.pocketsteward.app.saved.HierarchyTemplate
import java.util.Locale

/** Configured relative paths are conventions; only live verified paths become home evidence. */
class ProjectRoleCatalog(profiles: List<Map<String, String>> = emptyList()) {
    private val defaults = ProjectKnowledge.ROLE_FOLDERS.associateWith { it }
    private val paths = (listOf(defaults) + profiles.map { profile ->
        HierarchyTemplate.parse(HierarchyTemplate(profile).encode())
        defaults + profile
    }).flatMap { it.entries }.distinctBy { it.key to it.value }.filter { it.value.isNotEmpty() }
    val rootNames: List<String> = paths.map { it.value.substringBefore('/').lowercase(Locale.ROOT) }.distinct()

    /** A competing convention for one role is ambiguous, even when another role agrees. */
    fun observedRoles(observed: String): Map<String, String>? {
        val roots = observed.split('\n').filter { it.isNotBlank() }.groupBy { it.lowercase(Locale.ROOT) }
        if (roots.isEmpty() || roots.any { it.key !in rootNames || it.value.distinct().size > 1 }) return null
        val candidates = paths.mapNotNull { (role, path) ->
            val spelling = roots[path.substringBefore('/').lowercase(Locale.ROOT)]?.distinct()?.singleOrNull() ?: return@mapNotNull null
            role to spelling + path.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
        }.groupBy({ it.first }, { it.second })
        if (candidates.any { it.value.distinct().size > 1 }) return null
        return candidates.mapValues { it.value.single() }
    }
}
