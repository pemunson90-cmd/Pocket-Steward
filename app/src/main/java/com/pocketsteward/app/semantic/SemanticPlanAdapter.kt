package com.pocketsteward.app.semantic

import com.pocketsteward.app.ai.CoherenceClass
import com.pocketsteward.app.cleanup.SortCandidate
import com.pocketsteward.app.cleanup.SortScope
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue

data class SemanticSuggestion(
    val stableRef: String,
    val classification: CoherenceClass,
    val suggestedGroup: String?,
)

data class SemanticSkip(
    val stableRef: String,
    val reason: String,
)

data class SemanticPlanResult(
    val operations: List<PlannedOperation>,
    val plannedFileCount: Int,
    val skipped: List<SemanticSkip>,
    val authorizedDestinationRoots: List<FileRef.Direct> = emptyList(),
)

/**
 * Deterministic bridge from model advice to ordinary typed plan operations.
 *
 * The model never supplies a path. It supplies only a short group label.
 * This adapter resolves the current indexed file, chooses its originating
 * scan root, sanitizes the group to one path segment, and emits ordinary
 * CreateDirectory + Move proposals. Direct and SAF roots use the same typed
 * plan path; SAF destinations stay inside the already-granted tree.
 */
object SemanticPlanAdapter {
    const val MAX_GROUP_LENGTH = 64

    fun build(
        scopeRoots: List<FileRef>,
        records: List<FileRecord>,
        suggestions: List<SemanticSuggestion>,
        includeSubfolders: Boolean = false,
        destinationChoice: SemanticDestinationChoice =
            SemanticDestinationChoice(DestinationPolicy.ROOT_LOCAL),
        recommendedDocumentsRoot: FileRef.Direct? = null,
    ): SemanticPlanResult {
        require(scopeRoots.isNotEmpty()) { "Semantic planning needs at least one scope root." }

        val recordsByRef = records.associateBy { it.stableRef }
        val candidates = records.map { record ->
            SortCandidate(
                stableRef = record.stableRef,
                parentRef = record.parentRef,
                displayName = record.displayName,
                isDirectory = record.isDirectory,
            )
        }
        val protectedFolders = SortScope.protectedFolders(candidates)
        val operations = mutableListOf<PlannedOperation>()
        val createdDirectories = mutableSetOf<String>()
        val skipped = mutableListOf<SemanticSkip>()
        var plannedFiles = 0

        for ((stableRef, entries) in suggestions.groupBy { it.stableRef }) {
            if (entries.size != 1) {
                skipped += SemanticSkip(stableRef, "Conflicting or duplicate semantic findings.")
                continue
            }
            val suggestion = entries.single()

            if (suggestion.classification !in setOf(
                    CoherenceClass.QUESTIONABLE,
                    CoherenceClass.DOES_NOT_BELONG,
                )
            ) {
                skipped += SemanticSkip(stableRef, "Classification does not call for reorganization.")
                continue
            }

            val record = recordsByRef[stableRef]
            if (record == null || record.isDirectory) {
                skipped += SemanticSkip(stableRef, "File is no longer present in the current scan index.")
                continue
            }

            val root = originatingRoot(record, scopeRoots)
            if (root == null) {
                skipped += SemanticSkip(stableRef, "File is outside the selected scan roots.")
                continue
            }

            val candidate = candidates.first { it.stableRef == stableRef }
            if (SortScope.isProtected(candidate, protectedFolders, candidates)) {
                skipped += SemanticSkip(stableRef, "File is inside a protected folder.")
                continue
            }
            if (!includeSubfolders && !SortScope.isDirectlyInRoot(candidate, root.rawValue())) {
                skipped += SemanticSkip(stableRef, "File is already inside a folder; nested moves were not enabled.")
                continue
            }

            val group = sanitizeGroup(suggestion.suggestedGroup)
            if (group == null) {
                skipped += SemanticSkip(stableRef, "Suggested group is blank or unsafe.")
                continue
            }

            val destinationRoot: FileRef = when (destinationChoice.policy) {
                DestinationPolicy.ROOT_LOCAL -> root
                DestinationPolicy.RECOMMENDED_DOCUMENTS -> {
                    require(root is FileRef.Direct) {
                        "Recommended Documents is only available with full file-manager access."
                    }
                    requireNotNull(recommendedDocumentsRoot) {
                        "Recommended Documents policy needs the resolved public Documents root."
                    }
                }
                DestinationPolicy.EXPLICIT_FOLDER -> {
                    require(root is FileRef.Direct) {
                        "Explicit external destinations are only available with full file-manager access."
                    }
                    requireNotNull(destinationChoice.explicitRoot) {
                        "Explicit destination policy needs a chosen folder."
                    }
                }
            }

            // Prefer the provider-assigned/concrete reference when the group
            // already exists. A symbolic Child is used only before creation.
            val existingDirectory = records.firstOrNull { existing ->
                existing.isDirectory &&
                    existing.parentRef?.trimEnd('/') == destinationRoot.rawValue().trimEnd('/') &&
                    existing.displayName.equals(group, ignoreCase = true)
            }
            val destinationDirectory = existingDirectory
                ?.stableRef
                ?.let(::parseFileRef)
                ?: destinationRoot.child(group)

            if (record.parentRef?.trimEnd('/') == destinationDirectory.rawValue().trimEnd('/')) {
                skipped += SemanticSkip(stableRef, "File is already in the suggested group.")
                continue
            }

            val directoryKey = destinationDirectory.rawValue()
            if (existingDirectory == null && createdDirectories.add(directoryKey)) {
                operations += PlannedOperation.CreateDirectory(
                    parent = destinationRoot,
                    name = group,
                    reason = "Approved destination for semantic document grouping.",
                )
            }

            operations += PlannedOperation.Move(
                source = parseFileRef(record.stableRef),
                destination = destinationDirectory.child(record.displayName),
                reason = "Document audit suggested group “$group”; deterministic adapter built this proposal.",
            )
            plannedFiles++
        }

        val destinationRoots = operations
            .filterIsInstance<PlannedOperation.CreateDirectory>()
            .mapNotNull { it.parent as? FileRef.Direct }
            .filterNot { parent ->
                scopeRoots.filterIsInstance<FileRef.Direct>().any {
                    it.absolutePath.trimEnd('/') == parent.absolutePath.trimEnd('/')
                }
            }
            .distinctBy { it.absolutePath.trimEnd('/') }

        return SemanticPlanResult(
            operations = operations,
            plannedFileCount = plannedFiles,
            skipped = skipped,
            authorizedDestinationRoots = destinationRoots,
        )
    }

    fun sanitizeGroup(value: String?): String? {
        val trimmed = value
            ?.trim()
            ?.trimEnd('.', ' ')
            ?.take(MAX_GROUP_LENGTH)
            ?.trim()
            ?: return null
        if (trimmed.isBlank() || trimmed == "." || trimmed == "..") return null
        if ('/' in trimmed || '\\' in trimmed) return null
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    private fun originatingRoot(
        record: FileRecord,
        roots: List<FileRef>,
    ): FileRef? {
        val directRoots = roots.filterIsInstance<FileRef.Direct>()
        if (directRoots.isNotEmpty()) {
            return directRoots
                .filter { root ->
                    val base = root.absolutePath.trimEnd('/')
                    record.stableRef == base || record.stableRef.startsWith("$base/")
                }
                .maxByOrNull { it.absolutePath.trimEnd('/').length }
        }

        // The product currently supports one persisted SAF tree at a time.
        // Every record supplied here came from that tree's scan snapshot.
        return roots.singleOrNull()
    }
}
