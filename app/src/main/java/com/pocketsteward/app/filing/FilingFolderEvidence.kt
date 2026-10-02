package com.pocketsteward.app.filing

import java.util.Locale

/** Child evidence can inform a folder's owner, but can never turn children into separate moves. */
object FilingFolderEvidence {
    fun reconcile(result: InboxFilingResult, children: InboxFilingResult, ownerByChild: Map<String, String>,
        indexedEntryCounts: Map<String, Int>, expectedEntryCounts: Map<String, Int>): InboxFilingResult {
        val byFolder = children.decisions.filter { child ->
            child.projectHome != null && !child.projectHome.categoryHome && child.confidence != FilingConfidence.UNRESOLVED &&
                child.evidence.any { it.kind in setOf(FilingEvidenceKind.USER_MAPPING, FilingEvidenceKind.PROJECT_HOME,
                    FilingEvidenceKind.APK_PACKAGE, FilingEvidenceKind.APK_LABEL, FilingEvidenceKind.FILENAME,
                    FilingEvidenceKind.ARCHIVE_ENTRY, FilingEvidenceKind.INDEXED_CONTENT, FilingEvidenceKind.IMAGE_TEXT) }
        }.groupBy { ownerByChild[it.artifact.stableRef] }
        val ambiguousByFolder = children.decisions.filter { child -> child.evidence.any {
            it.kind in setOf(FilingEvidenceKind.PROJECT_AMBIGUITY, FilingEvidenceKind.DESTINATION_CONFLICT)
        } }.groupBy { ownerByChild[it.artifact.stableRef] }
        return InboxFilingResult(result.decisions.map { folder ->
            if (!folder.artifact.isDirectory) return@map folder
            val ref = folder.artifact.stableRef
            val members = byFolder[ref].orEmpty()
            val homes = members.groupBy { requireNotNull(it.projectHome).path.trimEnd('/').lowercase(Locale.ROOT) }
            val count = indexedEntryCounts[ref] ?: 0
            val expected = expectedEntryCounts[ref]
            val coverage = FilingEvidence(FilingEvidenceKind.FOLDER_CONTENT,
                "$count indexed entries inside this intact folder; ${members.size} files have project evidence. " +
                    if (expected == null || expected != count) "Descendant coverage is incomplete; refresh the inventory."
                    else "Filename and available verified cached content were considered. Uncached contents are not certified.", 1)
            val ambiguous = ambiguousByFolder[ref].orEmpty()
            if (homes.isEmpty() && ambiguous.isEmpty()) return@map folder.copy(evidence = folder.evidence + coverage)
            val original = folder.projectHome?.path?.trimEnd('/')?.lowercase(Locale.ROOT)
            val competing = ambiguous.isNotEmpty() || homes.size > 1 || original != null && original !in homes
            if (competing) {
                val names = (members.mapNotNull { it.projectName } + listOfNotNull(folder.projectName)).distinct()
                val explanation = FilingEvidence(FilingEvidenceKind.PROJECT_AMBIGUITY,
                    "Files inside this folder have competing project evidence" +
                        (if (names.isNotEmpty()) ": ${names.take(6).joinToString()}" else ": ${ambiguous.take(3).joinToString { it.artifact.displayName }}") +
                        ". Keep the folder intact and choose its owner.", 200)
                // An explicit remembered instruction is still visible and authoritative; inferred ownership is not.
                if (folder.evidence.any { it.kind == FilingEvidenceKind.USER_MAPPING }) return@map folder.copy(evidence = folder.evidence + coverage + explanation)
                return@map folder.copy(projectName = null, projectHome = null, release = null, destinationDirectory = null,
                    confidence = FilingConfidence.UNRESOLVED, createsProjectHome = false, evidence = folder.evidence + coverage + explanation)
            }
            val anchor = members.first()
            val home = requireNotNull(anchor.projectHome)
            val support = FilingEvidence(FilingEvidenceKind.FOLDER_CONTENT,
                "${members.size} contained files support ${home.name}; the folder travels intact. Review this ownership before moving.", 60)
            if (original != null) folder.copy(evidence = folder.evidence + coverage + support)
            else folder.copy(projectName = home.name, projectHome = home, release = null,
                destinationDirectory = home.path, confidence = FilingConfidence.PROBABLE,
                createsProjectHome = anchor.createsProjectHome, evidence = folder.evidence + coverage + support)
        })
    }
}
