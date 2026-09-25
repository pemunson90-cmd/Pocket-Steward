package com.pocketsteward.app.filing

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import java.io.File

data class FilingReviewItem(
    val sourceRef: String,
    val displayName: String,
    val sizeBytes: Long,
    val confidence: FilingConfidence,
    val evidence: List<String>,
    val destinationPath: String?,
)

data class FilingReviewGroup(
    val projectName: String,
    val projectHomePath: String,
    val destinationPath: String,
    val existingProjectHome: Boolean,
    val release: String?,
    val items: List<FilingReviewItem>,
    val editableDirectDestination: Boolean = true,
)

data class FilingReviewPresentation(
    val groups: List<FilingReviewGroup>,
    val unresolved: List<FilingReviewItem>,
) {
    val proposedCount: Int get() = groups.sumOf { it.items.size }
    val unresolvedCount: Int get() = unresolved.size
}

data class InboxFilingPlan(
    val operations: List<PlannedOperation>,
    val authorizedDestinationRoots: List<FileRef.Direct>,
    val defaultSelectedSourceRefs: Set<String>,
    val presentation: FilingReviewPresentation,
)

/** Converts filing decisions into the same typed, reviewable plan used everywhere else. */
object InboxFilingPlanAdapter {
    fun build(
        result: InboxFilingResult,
        storageRoot: FileRef.Direct,
        existingDirectories: Set<String>,
    ): InboxFilingPlan {
        val existing = existingDirectories.mapTo(linkedSetOf()) { it.trimEnd('/').lowercase() }
        val operations = mutableListOf<PlannedOperation>()
        val plannedDirectories = linkedSetOf<String>()
        val authorization = linkedSetOf<String>()
        val selectedRefs = linkedSetOf<String>()

        val proposed = result.proposed.filter { decision ->
            decision.projectHome != null && decision.destinationDirectory != null
        }

        proposed.groupBy { decision ->
            Triple(
                requireNotNull(decision.projectHome).path.trimEnd('/'),
                requireNotNull(decision.destinationDirectory).trimEnd('/'),
                decision.projectName.orEmpty(),
            )
        }.forEach { (key, decisions) ->
            val (homePath, destinationPath, projectName) = key
            val homeRef = FileRef.Direct(homePath)
            val homeExists = homePath.lowercase() in existing

            if (!homeExists) {
                val parent = File(homePath).parentFile?.absolutePath?.trimEnd('/')
                require(parent != null && parent.equals(storageRoot.absolutePath.trimEnd('/'), ignoreCase = true)) {
                    "A new project home may only be created directly under the authorized storage root."
                }
                if (plannedDirectories.add(homePath.lowercase())) {
                    operations += PlannedOperation.CreateDirectory(
                        parent = storageRoot,
                        name = File(homePath).name,
                        reason = "Create the reviewed project home for $projectName.",
                    )
                }
                authorization += storageRoot.absolutePath.trimEnd('/')
            } else {
                authorization += homePath
            }

            if (!destinationPath.equals(homePath, ignoreCase = true)) {
                val releaseName = File(destinationPath).name
                if (plannedDirectories.add(destinationPath.lowercase())) {
                    operations += PlannedOperation.CreateDirectory(
                        parent = homeRef,
                        name = releaseName,
                        reason = "Create/use the reviewed release folder for $projectName.",
                    )
                }
            }

            decisions.forEach { decision ->
                val destination = FileRef.Direct(destinationPath).child(decision.artifact.displayName)
                operations += PlannedOperation.Move(
                    source = parseFileRef(decision.artifact.stableRef),
                    destination = destination,
                    reason = buildString {
                        append("Inbox filing · ")
                        append(decision.confidence.name.lowercase())
                        append(" match to ")
                        append(projectName)
                        decision.release?.let { append(" · release ").append(it) }
                        if (decision.evidenceSummary.isNotBlank()) append(" · ").append(decision.evidenceSummary)
                    },
                )
                if (decision.confidence == FilingConfidence.STRONG) {
                    selectedRefs += decision.artifact.stableRef
                }
            }
        }

        val groups = proposed
            .groupBy { requireNotNull(it.destinationDirectory).trimEnd('/') }
            .map { (destination, decisions) ->
                val first = decisions.first()
                val home = requireNotNull(first.projectHome)
                FilingReviewGroup(
                    projectName = first.projectName.orEmpty(),
                    projectHomePath = home.path.trimEnd('/'),
                    destinationPath = destination,
                    existingProjectHome = home.path.trimEnd('/').lowercase() in existing,
                    release = first.release,
                    items = decisions.map(::reviewItem),
                    editableDirectDestination = home.path.trimEnd('/').lowercase() in existing,
                )
            }
            .sortedWith(compareBy<FilingReviewGroup> { it.projectName.lowercase() }.thenBy { it.release.orEmpty() })

        val unresolved = result.unresolved.map(::reviewItem)

        return InboxFilingPlan(
            operations = operations,
            authorizedDestinationRoots = authorization.map { FileRef.Direct(it) },
            defaultSelectedSourceRefs = selectedRefs,
            presentation = FilingReviewPresentation(groups, unresolved),
        )
    }

    private fun reviewItem(decision: FilingDecision): FilingReviewItem = FilingReviewItem(
        sourceRef = decision.artifact.stableRef,
        displayName = decision.artifact.displayName,
        sizeBytes = decision.artifact.sizeBytes,
        confidence = decision.confidence,
        evidence = decision.evidence.sortedByDescending { it.weight }.map { it.detail }.distinct().take(4),
        destinationPath = decision.destinationDirectory,
    )
}


/**
 * Same filing semantics inside one already-granted SAF tree. Existing project
 * folders are preferred, but a new project/release hierarchy can still be
 * expressed with typed [FileRef.Child] references. No URI is invented.
 */
object InboxFilingSafPlanAdapter {
    fun build(
        result: InboxFilingResult,
        scopeRoot: FileRef,
        existingHomes: Map<String, FileRef>,
        scopeLabel: String,
    ): InboxFilingPlan {
        val operations = mutableListOf<PlannedOperation>()
        val plannedDirectories = linkedSetOf<String>()
        val selectedRefs = linkedSetOf<String>()
        val groups = mutableListOf<FilingReviewGroup>()

        val proposed = result.proposed.filter { it.projectHome != null && it.destinationDirectory != null }
        proposed.groupBy { decision ->
            val home = requireNotNull(decision.projectHome)
            home.path.trimEnd('/') to decision.release
        }.forEach { (_, decisions) ->
            val first = decisions.first()
            val home = requireNotNull(first.projectHome)
            val projectName = requireNotNull(first.projectName)
            val existingHomeRef = existingHomes[home.path.trimEnd('/')]
            val homeRef = existingHomeRef ?: scopeRoot.child(
                InboxFilingEngine.sanitizeSegment(projectName)
                    ?: error("Unsafe project folder name: $projectName"),
            )
            if (existingHomeRef == null) {
                val safeProjectName = (homeRef as FileRef.Child).name
                if (plannedDirectories.add(homeRef.rawValue())) {
                    operations += PlannedOperation.CreateDirectory(
                        parent = scopeRoot,
                        name = safeProjectName,
                        reason = "Create the reviewed project home for $projectName inside the granted tree.",
                    )
                }
            }

            val release = first.release
            val destinationRef = if (home.hierarchy == com.pocketsteward.app.saved.ProjectHierarchyStrategy.VERSIONED && release != null) {
                val safeRelease = InboxFilingEngine.sanitizeSegment(release) ?: error("Unsafe release folder: $release")
                val releaseRef = homeRef.child(safeRelease)
                if (plannedDirectories.add(releaseRef.rawValue())) {
                    operations += PlannedOperation.CreateDirectory(
                        parent = homeRef,
                        name = safeRelease,
                        reason = "Create/use the reviewed release folder for $projectName.",
                    )
                }
                releaseRef
            } else {
                homeRef
            }

            decisions.forEach { decision ->
                operations += PlannedOperation.Move(
                    source = parseFileRef(decision.artifact.stableRef),
                    destination = destinationRef.child(decision.artifact.displayName),
                    reason = buildString {
                        append("Inbox filing · ")
                        append(decision.confidence.name.lowercase())
                        append(" match to ").append(projectName)
                        release?.let { append(" · release ").append(it) }
                        if (decision.evidenceSummary.isNotBlank()) append(" · ").append(decision.evidenceSummary)
                    },
                )
                if (decision.confidence == FilingConfidence.STRONG) selectedRefs += decision.artifact.stableRef
            }

            val displayDestination = buildString {
                append(scopeLabel).append(" › ").append(projectName)
                release?.let { append(" › ").append(it) }
            }
            groups += FilingReviewGroup(
                projectName = projectName,
                projectHomePath = if (existingHomeRef != null) home.path else displayDestination.substringBeforeLast(" › ", displayDestination),
                destinationPath = displayDestination,
                existingProjectHome = existingHomeRef != null,
                release = release,
                items = decisions.map { decision ->
                    FilingReviewItem(
                        sourceRef = decision.artifact.stableRef,
                        displayName = decision.artifact.displayName,
                        sizeBytes = decision.artifact.sizeBytes,
                        confidence = decision.confidence,
                        evidence = decision.evidence.sortedByDescending { it.weight }.map { it.detail }.distinct().take(4),
                        destinationPath = displayDestination,
                    )
                },
                editableDirectDestination = false,
            )
        }

        return InboxFilingPlan(
            operations = operations,
            authorizedDestinationRoots = emptyList(),
            defaultSelectedSourceRefs = selectedRefs,
            presentation = FilingReviewPresentation(
                groups = groups.sortedWith(compareBy<FilingReviewGroup> { it.projectName.lowercase() }.thenBy { it.release.orEmpty() }),
                unresolved = result.unresolved.map { decision ->
                    FilingReviewItem(
                        sourceRef = decision.artifact.stableRef,
                        displayName = decision.artifact.displayName,
                        sizeBytes = decision.artifact.sizeBytes,
                        confidence = decision.confidence,
                        evidence = decision.evidence.sortedByDescending { it.weight }.map { it.detail }.distinct().take(4),
                        destinationPath = null,
                    )
                },
            ),
        )
    }
}