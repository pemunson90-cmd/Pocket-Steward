package com.pocketsteward.app.semantic

import com.pocketsteward.app.ai.CoherenceClass
import com.pocketsteward.app.cleanup.SortCandidate
import com.pocketsteward.app.cleanup.SortScope
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef

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
)

/**
 * Deterministic bridge from model advice to ordinary typed plan operations.
 *
 * The model never supplies a path. It supplies only a short group label.
 * This adapter resolves the current indexed file, chooses its originating
 * scan root, sanitizes the group to one path segment, and emits ordinary
 * CreateDirectory + Move proposals.
 */
object SemanticPlanAdapter {
    const val MAX_GROUP_LENGTH = 64

    fun build(
        scopeRoots: List<FileRef.Direct>,
        records: List<FileRecord>,
        suggestions: List<SemanticSuggestion>,
        includeSubfolders: Boolean = false,
    ): SemanticPlanResult {
        require(scopeRoots.isNotEmpty()) { "Semantic planning needs at least one direct scope root." }

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

            val root = originatingRoot(record.stableRef, scopeRoots)
            if (root == null) {
                skipped += SemanticSkip(stableRef, "File is outside the selected scan roots.")
                continue
            }

            val candidate = candidates.first { it.stableRef == stableRef }
            if (SortScope.isProtected(candidate, protectedFolders)) {
                skipped += SemanticSkip(stableRef, "File is inside a protected folder.")
                continue
            }
            if (!includeSubfolders && !SortScope.isDirectlyInRoot(candidate, root.absolutePath)) {
                skipped += SemanticSkip(stableRef, "File is already inside a folder; nested moves were not enabled.")
                continue
            }

            val group = sanitizeGroup(suggestion.suggestedGroup)
            if (group == null) {
                skipped += SemanticSkip(stableRef, "Suggested group is blank or unsafe.")
                continue
            }

            val destinationDirectory = "${root.absolutePath.trimEnd('/')}/$group"
            if (record.parentRef?.trimEnd('/') == destinationDirectory.trimEnd('/')) {
                skipped += SemanticSkip(stableRef, "File is already in the suggested group.")
                continue
            }

            if (createdDirectories.add(destinationDirectory)) {
                operations += PlannedOperation.CreateDirectory(
                    parent = root,
                    name = group,
                    reason = "Destination suggested by the read-only coherence audit.",
                )
            }
            operations += PlannedOperation.Move(
                source = FileRef.Direct(record.stableRef),
                destination = FileRef.Direct("$destinationDirectory/${record.displayName}"),
                reason = "Coherence audit suggested group “$group”; deterministic adapter built this proposal.",
            )
            plannedFiles++
        }

        return SemanticPlanResult(
            operations = operations,
            plannedFileCount = plannedFiles,
            skipped = skipped,
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
        stableRef: String,
        roots: List<FileRef.Direct>,
    ): FileRef.Direct? =
        roots
            .filter { root ->
                val base = root.absolutePath.trimEnd('/')
                stableRef == base || stableRef.startsWith("$base/")
            }
            .maxByOrNull { it.absolutePath.trimEnd('/').length }
}
