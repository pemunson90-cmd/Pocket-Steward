package com.pocketsteward.app.intent

import com.pocketsteward.app.cleanup.CleanupScopeReport
import com.pocketsteward.app.cleanup.GeneratedCleanup
import com.pocketsteward.app.cleanup.SortCandidate
import com.pocketsteward.app.cleanup.SortScope
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.rules.RuleEngine
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.parseFileRef

/**
 * Converts a parsed organize/group/archive request into ordinary typed
 * operations. It never crosses scan roots: callers invoke it once per root.
 */
object IntentPlanGenerator {
    fun generate(
        scopeRoot: FileRef.Direct,
        records: List<FileRecord>,
        projectKeywords: List<ProjectKeyword>,
        intent: BoundedIntent,
    ): GeneratedCleanup {
        require(intent.action in setOf(IntentAction.ORGANIZE, IntentAction.GROUP, IntentAction.ARCHIVE))

        val partition = SortScope.partition(
            candidates = records.map { it.toSortCandidate() },
            scopeRoot = scopeRoot.absolutePath,
            includeSubfolders = intent.includeSubfolders,
        )
        val sortableRefs = partition.sortable.mapTo(mutableSetOf()) { it.stableRef }
        val operations = mutableListOf<PlannedOperation>()
        val plannedDirectories = mutableSetOf<String>()
        var sorted = 0

        for (record in records) {
            if (record.stableRef !in sortableRefs) continue

            val classification = RuleEngine.classify(record.displayName, record.extension, projectKeywords)
            if (classification.confidence < 1.0f) continue
            if (intent.categories.isNotEmpty() && classification.category !in intent.categories) continue

            val leafFolder = when (intent.groupingMode) {
                GroupingMode.TYPE -> classification.category.folderName()
                GroupingMode.PROJECT -> classification.projectFolder ?: continue
            }
            val segments = buildList {
                intent.mainFolder?.takeIf { it.isNotBlank() }?.let(::add)
                add(leafFolder)
            }
            val destinationFolder = ensureDirectoryTree(
                root = scopeRoot,
                segments = segments,
                operations = operations,
                plannedDirectories = plannedDirectories,
                reason = "Destination for ${classification.reason}",
            )
            operations += PlannedOperation.Move(
                source = parseFileRef(record.stableRef),
                destination = FileRef.Direct(
                    "${destinationFolder.absolutePath.trimEnd('/')}/${record.displayName}",
                ),
                reason = classification.reason,
            )
            sorted++
        }

        return GeneratedCleanup(
            plan = AgentPlan(intent.rawRequest, operations),
            scopeReport = CleanupScopeReport(
                protectedFolderCount = partition.protectedFolders.size,
                skippedByProtection = partition.skippedByProtection,
                skippedByDepth = partition.skippedByDepth,
                sortedCount = sorted,
            ),
        )
    }

    private fun ensureDirectoryTree(
        root: FileRef.Direct,
        segments: List<String>,
        operations: MutableList<PlannedOperation>,
        plannedDirectories: MutableSet<String>,
        reason: String,
    ): FileRef.Direct {
        var parent = root
        for (rawSegment in segments) {
            val segment = rawSegment.trim()
            require(segment.isNotBlank() && '/' !in segment && '\\' !in segment && segment != "..") {
                "Unsafe destination folder name: $rawSegment"
            }
            val path = "${parent.absolutePath.trimEnd('/')}/$segment"
            if (plannedDirectories.add(path)) {
                operations += PlannedOperation.CreateDirectory(
                    parent = parent,
                    name = segment,
                    reason = reason,
                )
            }
            parent = FileRef.Direct(path)
        }
        return parent
    }
}

private fun FileRecord.toSortCandidate(): SortCandidate = SortCandidate(
    stableRef = stableRef,
    parentRef = parentRef,
    displayName = displayName,
    isDirectory = isDirectory,
)

private fun FileCategory.folderName(): String = when (this) {
    FileCategory.IMAGE -> "Images"
    FileCategory.DOCUMENT -> "Documents"
    FileCategory.APK -> "APKs"
    FileCategory.ARCHIVE -> "Archives"
    FileCategory.AUDIO_VIDEO -> "Audio Video"
    FileCategory.OTHER -> "Other"
}
