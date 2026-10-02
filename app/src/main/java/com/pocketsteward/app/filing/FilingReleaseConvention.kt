package com.pocketsteward.app.filing

import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import java.util.Locale

/** Preserve observed layout/spelling without resolving competing release identities by guessing. */
object FilingReleaseConvention {
    fun reconcile(result: InboxFilingResult, existingDirectories: Set<String>, unavailableHomes: Map<String, String> = emptyMap()): InboxFilingResult {
        val paths = existingDirectories.groupBy { key(it.trimEnd('/')) }
        val children = existingDirectories.groupBy { key(it.trimEnd('/').substringBeforeLast('/')) }
        data class Layout(val roots: List<String>, val releases: Map<String, List<String>>, val parents: List<String>, val prefixes: List<String>)
        val layouts = mutableMapOf<ProjectHomeCandidate, Layout>()
        return InboxFilingResult(result.decisions.map { decision ->
            val home = decision.projectHome ?: return@map decision
            val release = decision.release ?: return@map decision
            if (home.hierarchy !in setOf(ProjectHierarchyStrategy.VERSIONED, ProjectHierarchyStrategy.PROJECT_ROLES) ||
                decision.artifact.isDirectory || decision.confidence == FilingConfidence.UNRESOLVED) return@map decision
            fun blocked(detail: String) = decision.copy(destinationDirectory = null, confidence = FilingConfidence.UNRESOLVED,
                evidence = listOf(FilingEvidence(FilingEvidenceKind.DESTINATION_CONFLICT, detail, 100)) + decision.evidence)
            unavailableHomes[home.path]?.let { return@map blocked("Release layout could not be verified: $it") }
            val layout = layouts.getOrPut(home) {
                val roots = releaseParents(home)
                val observed = roots.flatMap { children[key(it)].orEmpty() }.distinct()
                val siblings = observed.filter { isReleaseName(it.substringAfterLast('/')) }
                Layout(roots, observed.groupBy { canonical(it.substringAfterLast('/')) },
                    siblings.map { it.substringBeforeLast('/') }.distinct(),
                    siblings.map { it.substringAfterLast('/').take(1).takeIf { prefix -> prefix.equals("v", true) }.orEmpty() }.distinct())
            }
            val candidates = layout.releases[canonical(release)].orEmpty()
            if (candidates.size > 1) return@map blocked("Multiple existing folders represent release $release; choose the intended release folder.")
            val releasePath = candidates.singleOrNull() ?: run {
                val parents = layout.parents
                if (parents.size > 1) return@map blocked("Existing releases use competing project layouts; choose a release folder.")
                val prefixes = layout.prefixes
                if (prefixes.size > 1) return@map blocked("Existing releases use competing version prefixes; choose a release folder.")
                val parent = parents.singleOrNull() ?: layout.roots.last()
                val exactParents = paths[key(parent)].orEmpty()
                if (exactParents.size > 1) return@map blocked("Multiple release parent folders differ only by spelling; choose the intended folder.")
                val name = prefixes.singleOrNull().orEmpty() + release.removePrefix("v").removePrefix("V")
                "${exactParents.singleOrNull() ?: parent}/$name"
            }
            val defaultRoot = InboxFilingEngine.releaseDestination(home, release, decision.artifact, FilingRole.ROOT)
            val desired = InboxFilingEngine.releaseDestination(home, release, decision.artifact)
            val suffix = desired.removePrefix(defaultRoot).trim('/')
            var destination = releasePath
            for (segment in suffix.split('/').filter { it.isNotBlank() }) {
                val spelling = paths[key("$destination/$segment")].orEmpty()
                if (spelling.size > 1) return@map blocked("Multiple role folders differ only by spelling in release $release; choose the intended folder.")
                destination = spelling.singleOrNull() ?: "$destination/$segment"
            }
            decision.copy(destinationDirectory = destination, release = releasePath.substringAfterLast('/'))
        })
    }

    fun releaseParents(home: ProjectHomeCandidate): List<String> {
        val base = home.path.trimEnd('/')
        if (home.hierarchy != ProjectHierarchyStrategy.PROJECT_ROLES) return listOf(base)
        val versions = (home.roleFolders["Versions"] ?: "Versions").trim('/')
        return listOf(base, base + if (versions.isEmpty()) "" else "/$versions").distinct()
    }
    fun isReleaseName(name: String): Boolean = releaseName.matches(name)
    private val releaseName = Regex("(?i)v?\\d+(?:\\.\\d+){0,3}(?:[-_](?:dev|alpha|beta|rc|build|hb)[a-z0-9.-]*)?(?:\\+[a-z0-9]+(?:[.-][a-z0-9]+)*)?")
    private fun canonical(value: String): String = value.trim().removePrefix("v").removePrefix("V").lowercase(Locale.ROOT)
    private fun key(value: String): String = value.lowercase(Locale.ROOT)
}
