package com.pocketsteward.app.filing

import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

data class FilingReleaseLayout(val directories: Set<String>, val unavailableHomes: Map<String, String>)

/** Live, read-only observation of only homes relevant to this review. No 100-home cutoff. */
object FilingReleaseDiscovery {
    suspend fun discover(result: InboxFilingResult, gateway: StorageGateway, existingHomes: Map<String, FileRef> = emptyMap(), onProgress: (Int, Int) -> Unit = { _, _ -> }): FilingReleaseLayout {
        val decisions = result.proposed.filter { !it.artifact.isDirectory && it.release != null &&
            it.projectHome?.hierarchy in setOf(ProjectHierarchyStrategy.VERSIONED, ProjectHierarchyStrategy.PROJECT_ROLES) }
        val homes = decisions.groupBy { requireNotNull(it.projectHome) }
        val directories = linkedSetOf<String>()
        val unavailable = linkedMapOf<String, String>()
        var observations = 0
        for ((index, entry) in homes.entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            val home = entry.key
            try {
                require(observations < MAX_OBSERVATIONS) { "Release discovery reached its safety limit; narrow this review or choose release folders explicitly." }
                val listing = mutableMapOf<String, List<String>>()
                // Display-name paths describe conventions; only concrete provider references perform reads.
                val references = mutableMapOf<String, FileRef>()
                val base = home.path.trimEnd('/')
                if (base.startsWith("content://") || base.startsWith("ps-child:")) {
                    existingHomes[base]?.let { references[base] = it }
                } else references[base] = FileRef.Direct(base)
                suspend fun children(path: String): List<String> {
                    listing[path]?.let { return it }
                    currentCoroutineContext().ensureActive()
                    val ref = references[path] ?: return emptyList<String>().also { listing[path] = it }
                    if (!gateway.exists(ref)) return emptyList<String>().also { listing[path] = it }
                    require(gateway.stat(ref).isDirectory) { "A required release folder is now a file." }
                    val observed = gateway.listChildren(ref)
                    observations += observed.size
                    require(observations <= MAX_OBSERVATIONS) { "Release discovery reached its safety limit; narrow this review or choose release folders explicitly." }
                    val folders = observed.filter { it.isDirectory }.map { child ->
                        val logical = "$path/${child.displayName}"
                        references[logical] = child.ref
                        logical
                    }
                    directories += path
                    directories += folders
                    listing[path] = folders
                    return folders
                }
                suspend fun walk(base: String, suffix: String): List<String> {
                    var parents = listOf(base)
                    for (segment in suffix.split('/').filter { it.isNotBlank() }) {
                        parents = parents.flatMap { parent -> children(parent).filter { it.substringAfterLast('/').equals(segment, true) } }
                        require(parents.size <= 16) { "Competing folder spellings require an explicit release choice." }
                    }
                    return parents
                }
                val roots = FilingReleaseConvention.releaseParents(home).flatMap { root ->
                    if (root == base) listOf(base) else walk(base, root.removePrefix("$base/"))
                }.distinct()
                val releases = roots.flatMap { children(it) }.distinct()
                val wanted = entry.value.map { requireNotNull(it.release).removePrefix("v").removePrefix("V").lowercase(Locale.ROOT) }.toSet()
                for (release in releases.filter { it.substringAfterLast('/').removePrefix("v").removePrefix("V").lowercase(Locale.ROOT) in wanted }) {
                    val suffixes = entry.value.map { decision ->
                        val root = InboxFilingEngine.releaseDestination(home, requireNotNull(decision.release), decision.artifact, FilingRole.ROOT)
                        InboxFilingEngine.releaseDestination(home, decision.release, decision.artifact).removePrefix(root).trim('/')
                    }.distinct()
                    for (suffix in suffixes) walk(release, suffix)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { unavailable[home.path] = failure.message ?: failure.javaClass.simpleName }
            onProgress(index + 1, homes.size)
        }
        return FilingReleaseLayout(directories, unavailable)
    }
    private const val MAX_OBSERVATIONS = 100_000
}
