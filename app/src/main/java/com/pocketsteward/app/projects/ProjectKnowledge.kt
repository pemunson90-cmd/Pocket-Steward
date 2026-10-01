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
import java.io.File
import java.nio.file.Files
import java.util.Locale

data class ProjectKnowledgeResult(val homes: List<ProjectHomeCandidate>, val examined: Int, val limited: Boolean) {
    val explanation: String get() = "Existing project layouts: ${homes.size} verified from $examined indexed candidates" +
        if (limited) " (first ${ProjectKnowledge.MAX_CANDIDATES}; refresh/configure homes for broader coverage)." else "."
}

/** Derived, read-only project knowledge; remembered user homes remain authoritative preferences. */
class ProjectKnowledge(private val records: FileRecordDao, private val gateway: StorageGateway) {
    suspend fun discover(storageRoot: FileRef.Direct, inboxes: List<String>): ProjectKnowledgeResult {
        val candidates = records.indexedProjectLayouts(storageRoot.absolutePath, ROLE_NAMES, MAX_CANDIDATES + 1)
        val homes = mutableListOf<ProjectHomeCandidate>()
        for (record in candidates.take(MAX_CANDIDATES)) {
            currentCoroutineContext().ensureActive()
            val candidate = ProjectKnowledgePolicy.candidate(storageRoot.absolutePath, inboxes, record) ?: continue
            try {
                val file = File(candidate.path)
                if (file.canonicalPath != file.absolutePath || Files.isSymbolicLink(file.toPath())) continue
                if (!gateway.stat(FileRef.Direct(candidate.path)).isDirectory) continue
                // Verify the layout now; six direct stats avoid walking a large project's full contents.
                var roleFound = false
                val verifiedRoles = linkedMapOf<String, String>()
                for ((standardRole, role) in candidate.roleFolders) {
                    val child = File(file, role)
                    if (!Files.isSymbolicLink(child.toPath()) && gateway.stat(FileRef.Direct(child.absolutePath)).isDirectory) {
                        roleFound = true
                        verifiedRoles[standardRole] = role
                    }
                }
                if (roleFound) homes += candidate.copy(roleFolders = verifiedRoles)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Missing/revoked/moved observations cannot become destinations. */ }
        }
        return ProjectKnowledgeResult(homes.distinctBy { it.path }, minOf(candidates.size, MAX_CANDIDATES), candidates.size > MAX_CANDIDATES)
    }

    companion object {
        const val MAX_CANDIDATES = 200
        val ROLE_FOLDERS = listOf("Manuscript", "Notes", "Drafts", "Images", "Versions", "Archive")
        val ROLE_NAMES = ROLE_FOLDERS.map { it.lowercase(Locale.ROOT) }
    }
}

object ProjectKnowledgePolicy {
    private val generic = setOf("android", "download", "downloads", "documents", "pictures", "movies", "music", "dcim",
        "alarms", "notifications", "ringtones", "podcasts", "audiobooks", "pocketsteward", "trash", "uncertain",
        "manuscript", "manuscripts", "notes", "drafts", "images", "versions", "archive", "archives", "projects", "assets",
        "writing", "research", "content", "media", "files", "resources")

    fun candidate(storageRoot: String, inboxes: List<String>, record: IndexedProjectLayout): ProjectHomeCandidate? {
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
        val groupedRoles = record.observedRoles.split('\n').filter { it.isNotBlank() }
            .groupBy { it.lowercase(Locale.ROOT) }
        if (groupedRoles.isEmpty() || groupedRoles.any { (role, values) -> role !in ProjectKnowledge.ROLE_NAMES || values.distinct().size > 1 }) return null
        val roles = ProjectKnowledge.ROLE_FOLDERS.mapNotNull { role ->
            groupedRoles[role.lowercase(Locale.ROOT)]?.distinct()?.singleOrNull()?.let { role to it }
        }.toMap()
        return ProjectHomeCandidate(record.name, path, aliases = listOf(record.name),
            hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = false, roleFolders = roles)
    }
}
