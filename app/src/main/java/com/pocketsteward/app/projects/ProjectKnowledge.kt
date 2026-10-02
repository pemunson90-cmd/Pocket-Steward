package com.pocketsteward.app.projects

import com.pocketsteward.app.data.db.IndexedProjectLayout
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.filing.ProjectHomeCandidate
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.util.Locale

data class ProjectKnowledgeResult(val homes: List<ProjectHomeCandidate>, val examined: Int, val limited: Boolean) {
    val explanation: String get() = "Existing project layouts: ${homes.size} verified from $examined indexed candidates" +
        if (limited) " (the library changed during discovery or a safety limit was reached; rebuild the review for complete coverage)." else "."
}

/** Derived, read-only project knowledge; remembered user homes remain authoritative preferences. */
class ProjectKnowledge(private val records: FileRecordDao, private val gateway: StorageGateway,
    private val checkpoints: ProjectDiscoveryStore? = null,
) {
    private val discovery = kotlinx.coroutines.sync.Mutex()
    suspend fun discover(storageRoot: FileRef.Direct, inboxes: List<String>, roleProfiles: List<Map<String, String>> = emptyList(),
        onProgress: (Int) -> Unit = {},
    ): ProjectKnowledgeResult = discovery.withLock {
        val revision = records.projectDiscoveryRevision()
        val catalog = ProjectRoleCatalog(roleProfiles)
        val scopes = records.getKnownScopeRoots().filter { it == storageRoot.absolutePath || it.startsWith(storageRoot.absolutePath.trimEnd('/') + "/") }
        val keySource = listOf("project-discovery-v2", storageRoot.absolutePath, inboxes.sorted().joinToString("\u0000"),
            roleProfiles.map { it.toSortedMap().toString() }.sorted().joinToString("\u0000"), revision.toString(), scopes.sorted().joinToString("\u0000")).joinToString("\n")
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(keySource.toByteArray()).joinToString("") { "%02x".format(it) }
        val saved = checkpoints?.load(key)
        val candidates = saved?.rows?.toMutableList() ?: mutableListOf()
        var complete = saved?.complete == true
        var after = candidates.lastOrNull()?.path.orEmpty()
        // Query all indexed memberships under this storage root, in stable pages. The old
        // broad-root-only query silently missed homes indexed by a separate authorized scan.
        while (!complete && candidates.size < MAX_DISCOVERY_CANDIDATES) {
            currentCoroutineContext().ensureActive()
            var page = emptyList<IndexedProjectLayout>()
            for (scope in scopes) {
                for (roleNames in catalog.rootNames.chunked(250)) {
                    currentCoroutineContext().ensureActive()
                    val observed = records.indexedProjectLayouts(scope, roleNames, MAX_CANDIDATES, after)
                    // Merge bounded pages incrementally. Never materialize every scope's page
                    // at once; overlapping memberships can repeat a home's observed roles.
                    page = (page + observed).groupBy { it.path }.map { (_, layouts) ->
                        layouts.first().copy(observedRoles = layouts.flatMap { it.observedRoles.split('\n') }.distinct().joinToString("\n"))
                    }.sortedWith(compareBy(ProjectDiscoveryOrder) { it.path }).take(MAX_CANDIDATES)
                }
            }
            if (page.isEmpty()) {
                complete = true
                runCatching { checkpoints?.append(key, emptyList()) }
            } else {
                require(ProjectDiscoveryOrder.compare(page.first().path, after) > 0) { "Project discovery cursor did not advance." }
                candidates += page
                after = page.last().path
                runCatching { checkpoints?.append(key, page) }
            }
            onProgress(candidates.size)
        }
        val homes = mutableListOf<ProjectHomeCandidate>()
        for ((index, record) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val candidate = ProjectKnowledgePolicy.candidate(storageRoot.absolutePath, inboxes, record, catalog) ?: continue
            try {
                val file = File(candidate.path)
                if (file.canonicalPath != file.absolutePath || Files.isSymbolicLink(file.toPath())) continue
                if (!gateway.stat(FileRef.Direct(candidate.path)).isDirectory) continue
                val verifiedRoles = linkedMapOf<String, String>()
                for ((standardRole, role) in candidate.roleFolders) {
                    currentCoroutineContext().ensureActive()
                    val child = File(file, role)
                    try {
                        if (child.canonicalPath == child.absolutePath && !Files.isSymbolicLink(child.toPath()) && gateway.stat(FileRef.Direct(child.absolutePath)).isDirectory) verifiedRoles[standardRole] = role
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Missing one role does not certify it or hide other observed roles. */ }
                }
                if (verifiedRoles.isNotEmpty()) homes += candidate.copy(roleFolders = verifiedRoles)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing or revoked home cannot become destination evidence. */ }
            if (index % MAX_CANDIDATES == 0) onProgress(index + 1)
        }
        ProjectKnowledgeResult(homes.distinctBy { it.path }, candidates.size, !complete || records.projectDiscoveryRevision() != revision)
    }
    companion object {
        const val MAX_CANDIDATES = 200 // Admission page size, not a corpus limit.
        const val MAX_DISCOVERY_CANDIDATES = 100_000
        val ROLE_FOLDERS = listOf("Manuscript", "Notes", "Drafts", "Images", "Versions", "Archive")
        val ROLE_NAMES = ROLE_FOLDERS.map { it.lowercase(Locale.ROOT) }
    }
}

object ProjectKnowledgePolicy {
    private val generic = setOf("android", "download", "downloads", "documents", "pictures", "movies", "music", "dcim",
        "alarms", "notifications", "ringtones", "podcasts", "audiobooks", "pocketsteward", "trash", "uncertain",
        "manuscript", "manuscripts", "notes", "drafts", "images", "versions", "archive", "archives", "projects", "assets",
        "writing", "research", "content", "media", "files", "resources")

    fun candidate(storageRoot: String, inboxes: List<String>, record: IndexedProjectLayout, catalog: ProjectRoleCatalog = ProjectRoleCatalog()): ProjectHomeCandidate? {
        if (record.hidden || record.name.isBlank()) return null
        val root = storageRoot.trimEnd('/')
        if (root.isBlank() || !root.startsWith('/')) return null
        val path = record.path.trimEnd('/')
        if (!path.startsWith("$root/")) return null
        val relative = path.removePrefix("$root/").split('/')
        if (relative.any { it.isBlank() || it == "." || it == ".." || it.startsWith('.') }) return null
        if (relative.first().lowercase(Locale.ROOT) in setOf("android", "download", "downloads", "trash", "uncertain", "pocketsteward")) return null
        if (record.name.lowercase(Locale.ROOT) in generic || relative.last() != record.name) return null
        if (inboxes.any { inbox -> path == inbox.trimEnd('/') || path.startsWith("${inbox.trimEnd('/')}/") }) return null
        val roles = catalog.observedRoles(record.observedRoles) ?: return null
        return ProjectHomeCandidate(record.name, path, aliases = listOf(record.name),
            hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = false, roleFolders = roles)
    }
}
