package com.pocketsteward.app.filing

import com.pocketsteward.app.saved.ProjectHierarchyStrategy

enum class FilingRole(val label: String, val folder: String?) {
    AUTO("Automatic", null), ROOT("Project root", null), MANUSCRIPT("Manuscript", "Manuscript"),
    NOTES("Notes", "Notes"), DRAFTS("Drafts", "Drafts"), IMAGES("Images", "Images"),
    VERSIONS("Versions", "Versions"), ARCHIVE("Archive", "Archive"),
}

/** Pure user correction: callers must rebuild and validate the whole typed plan. */
object FilingAssignments {
    fun assign(result: InboxFilingResult, sourceRefs: Set<String>, home: ProjectHomeCandidate, role: FilingRole): InboxFilingResult {
        require(sourceRefs.isNotEmpty()) { "Select some files first." }
        require(InboxFilingEngine.sanitizeSegment(home.name) == home.name) { "Use a safe project title." }
        require(result.decisions.mapTo(hashSetOf()) { it.artifact.stableRef }.containsAll(sourceRefs)) { "Some selected files are no longer in this filing review." }
        return InboxFilingResult(result.decisions.map { decision ->
            if (decision.artifact.stableRef !in sourceRefs) decision else {
                val release = InboxFilingEngine.releaseOf(decision.artifact)
                val createsHome = !home.persisted
                val destination = when {
                    decision.artifact.isDirectory && createsHome && decision.artifact.displayName == home.name -> home.path.substringBeforeLast('/')
                    decision.artifact.isDirectory || role == FilingRole.ROOT -> home.path
                    role == FilingRole.AUTO -> InboxFilingEngine.destinationFor(home, release, decision.artifact)
                    else -> (home.roleFolders[role.folder] ?: role.folder).let { folder -> if (folder.isNullOrBlank()) home.path else "${home.path.trimEnd('/')}/$folder" }
                }
                decision.copy(
                    projectName = home.name, projectHome = home, release = release,
                    destinationDirectory = destination, confidence = FilingConfidence.STRONG,
                    evidence = listOf(FilingEvidence(FilingEvidenceKind.USER_MAPPING, "You assigned this file to ${home.name} · ${role.label}", 100)) + decision.evidence,
                    createsProjectHome = createsHome,
                )
            }
        })
    }
}
