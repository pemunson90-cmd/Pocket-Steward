package com.pocketsteward.app.filing

import com.pocketsteward.app.saved.ProjectHierarchyStrategy

enum class FilingRole(val label: String, val folder: String?) {
    AUTO("Automatic", null), ROOT("Project root", null), MANUSCRIPT("Manuscript", "Manuscript"),
    NOTES("Notes", "Notes"), DRAFTS("Drafts", "Drafts"), IMAGES("Images", "Images"),
    VERSIONS("Versions", "Versions"), ARCHIVE("Archive", "Archive"),
}

/** Pure user correction: callers must rebuild and validate the whole typed plan. */
object FilingAssignments {
    fun assign(result: InboxFilingResult, sourceRefs: Set<String>, home: ProjectHomeCandidate, role: FilingRole, releaseFolder: String? = null): InboxFilingResult {
        require(releaseFolder == null || InboxFilingEngine.sanitizeSegment(releaseFolder) == releaseFolder) { "Release folder must be one safe folder name of at most 80 characters." }
        require(sourceRefs.isNotEmpty()) { "Select some files first." }
        require(InboxFilingEngine.sanitizeSegment(home.name) == home.name) { "Use a safe project title." }
        require(result.decisions.mapTo(hashSetOf()) { it.artifact.stableRef }.containsAll(sourceRefs)) { "Some selected files are no longer in this filing review." }
        return InboxFilingResult(result.decisions.map { decision ->
            if (decision.artifact.stableRef !in sourceRefs) decision else {
                val release = releaseFolder ?: InboxFilingEngine.releaseOf(decision.artifact)
                val createsHome = !home.persisted
                val destination = when {
                    releaseFolder != null -> explicitReleaseDestination(home, releaseFolder, role, decision.artifact)
                    decision.artifact.isDirectory && createsHome && decision.artifact.displayName == home.name -> home.path.substringBeforeLast('/')
                    decision.artifact.isDirectory || role == FilingRole.ROOT -> home.path
                    role == FilingRole.AUTO -> InboxFilingEngine.destinationFor(home, release, decision.artifact)
                    else -> (home.roleFolders[role.folder] ?: role.folder).let { folder -> if (folder.isNullOrBlank()) home.path else "${home.path.trimEnd('/')}/$folder" }
                }
                decision.copy(
                    projectName = home.name, projectHome = home, release = release,
                    destinationDirectory = destination, confidence = FilingConfidence.STRONG,
                    evidence = listOf(FilingEvidence(FilingEvidenceKind.USER_MAPPING, "You assigned this file to ${home.name} · ${role.label}${releaseFolder?.let { " · release $it" }.orEmpty()}", 100)) + decision.evidence,
                    createsProjectHome = createsHome,
                )
            }
        })
    }
    private fun explicitReleaseDestination(home: ProjectHomeCandidate, release: String, role: FilingRole, artifact: FilingArtifact): String {
        val base = home.path.trimEnd('/')
        if (home.hierarchy == ProjectHierarchyStrategy.VERSIONED || home.hierarchy == ProjectHierarchyStrategy.FLAT) return "$base/$release"
        if (home.hierarchy == ProjectHierarchyStrategy.CATEGORY) {
            val category = InboxFilingEngine.destinationFor(home, null, artifact).removePrefix(base).trim('/')
            return "$base/$release" + if (category.isBlank()) "" else "/$category"
        }
        val versions = (home.roleFolders["Versions"] ?: "Versions").trim('/')
        val releaseRoot = base + if (versions.isBlank()) "/$release" else "/$versions/$release"
        if (artifact.isDirectory || role == FilingRole.ROOT || role == FilingRole.VERSIONS) return releaseRoot
        val folder = if (role == FilingRole.AUTO) {
            InboxFilingEngine.destinationFor(home, null, artifact).removePrefix(base).trim('/')
        } else (home.roleFolders[role.folder] ?: role.folder).orEmpty().trim('/')
        return releaseRoot + if (folder.isBlank()) "" else "/$folder"
    }

}
