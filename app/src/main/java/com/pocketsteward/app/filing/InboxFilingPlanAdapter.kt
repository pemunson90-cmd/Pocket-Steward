package com.pocketsteward.app.filing

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.saved.ProjectHierarchyStrategy

data class FilingReviewItem(
    val sourceRef: String,
    val displayName: String,
    val sizeBytes: Long,
    val confidence: FilingConfidence,
    val evidence: List<String>,
    val destinationPath: String?,
    val isDirectory: Boolean = false,
    val contentExcerpt: String? = null,
)

data class FilingReviewGroup(
    val projectName: String,
    val projectHomePath: String,
    val destinationPath: String,
    val existingProjectHome: Boolean,
    val release: String?,
    val items: List<FilingReviewItem>,
    val hierarchy: ProjectHierarchyStrategy = ProjectHierarchyStrategy.VERSIONED,
    val editableDirectDestination: Boolean = true,
    val isUncertainCheckpoint: Boolean = false,
    val isTopicDestination: Boolean = false,
    val roleFolders: Map<String, String> = emptyMap(),
)

data class FilingReviewPresentation(
    val groups: List<FilingReviewGroup>,
    val unresolved: List<FilingReviewItem>,
    val checkpointGroups: List<FilingReviewGroup> = emptyList(),
    val skippedInboxFolders: Int = 0,
    val reviewingUncertain: Boolean = false,
    val imageCoverage: com.pocketsteward.app.image.ImageReviewCoverage? = null,
    val reviewSessionId: String? = null,
    /** Nullable for drafts written before explicit keep/complete inventory support. */
    val heldSourceRefs: Set<String>? = null,
    val retainedUncertainSourceRefs: Set<String>? = null,
    val indexedFolderDescendantCount: Int = 0,
) {
    val proposedCount: Int get() = groups.sumOf { it.items.size }
    val unresolvedCount: Int get() = unresolved.size
    val checkpointCount: Int get() = checkpointGroups.sumOf { it.items.size }
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
        retainedUncertainSourceRefs: Set<String> = emptySet(),
    ): InboxFilingPlan {
        require(result.decisions.map { it.artifact.stableRef }.distinct().size == result.decisions.size) { "Duplicate filing sources." }
        val existing = existingDirectories.mapTo(linkedSetOf()) { it.trimEnd('/').lowercase() }
        val operations = mutableListOf<PlannedOperation>()
        val plannedDirectories = linkedSetOf<String>()
        val authorization = linkedSetOf<String>()
        val selectedRefs = linkedSetOf<String>()
        val rootPath = storageRoot.absolutePath.trimEnd('/')

        fun ensureDirectory(path: String, projectName: String) {
            val normalized = path.trimEnd('/')
            require(normalized.startsWith("$rootPath/", ignoreCase = true)) {
                "A filing destination must be below the authorized storage root."
            }
            val parts = normalized.removePrefix("$rootPath/").split('/')
            require(parts.all { InboxFilingEngine.sanitizeSegment(it) == it }) {
                "A filing destination contains an unsafe folder name."
            }
            var parentPath = rootPath
            for (part in parts) {
                val childPath = "$parentPath/$part"
                if (childPath.lowercase() !in existing && plannedDirectories.add(childPath.lowercase())) {
                    operations += PlannedOperation.CreateDirectory(
                        parent = FileRef.Direct(parentPath),
                        name = part,
                        reason = "Create the reviewed folder for $projectName.",
                    )
                }
                parentPath = childPath
            }
        }

        val proposedDecisions = result.proposed
        val countsByHome = proposedDecisions.groupingBy { it.projectHome?.path?.lowercase() }.eachCount()
        val proposed = proposedDecisions.filter { decision ->
            decision.projectHome != null && decision.destinationDirectory != null
        }.map { decision ->
            val home = requireNotNull(decision.projectHome)
            // A folder can become the home only when no loose item also needs to
            // create that home. Otherwise keep the folder as an intact bundle:
            // merging its children would invalidate the reviewed source unit.
            if (decision.artifact.isDirectory && decision.createsProjectHome &&
                (countsByHome[home.path.lowercase()] ?: 0) > 1) {
                decision.copy(destinationDirectory = home.path)
            } else decision
        }

        proposed.sortedByDescending { it.artifact.isDirectory }.groupBy { decision ->
            Triple(
                requireNotNull(decision.projectHome).path.trimEnd('/'),
                requireNotNull(decision.destinationDirectory).trimEnd('/'),
                decision.projectName.orEmpty(),
            )
        }.forEach { (key, decisions) ->
            val (homePath, destinationPath, projectName) = key
            val homeRef = FileRef.Direct(homePath)
            val homeExists = homePath.lowercase() in existing

            val folderBecomesHome = decisions.size == 1 && decisions.single().artifact.isDirectory &&
                "$destinationPath/${decisions.single().artifact.displayName}".equals(homePath, ignoreCase = true)
            if (!homeExists) {
                if (folderBecomesHome) {
                    ensureDirectory(destinationPath, projectName)
                    plannedDirectories.add(homePath.lowercase())
                } else ensureDirectory(homePath, projectName)
                authorization += rootPath
            } else {
                authorization += homePath
            }

            if (!destinationPath.equals(homePath, ignoreCase = true)) {
                ensureDirectory(destinationPath, projectName)
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
                    hierarchy = home.hierarchy,
                    roleFolders = home.roleFolders,
                    isTopicDestination = home.hierarchy == ProjectHierarchyStrategy.FLAT && listOf("Images", "Music", "Movies").any { home.path.startsWith("$rootPath/$it/") },
                    editableDirectDestination = home.path.trimEnd('/').lowercase() in existing,
                )
            }
            .sortedWith(compareBy<FilingReviewGroup> { it.projectName.lowercase() }.thenBy { it.release.orEmpty() })

        val unresolved = (result.unresolved + result.proposed.filter { it.projectHome == null }).map(::reviewItem)
        val checkpointGroups = result.unresolved
            .filterNot { it.artifact.stableRef in retainedUncertainSourceRefs }
            .groupBy { it.artifact.parentRef?.trimEnd('/') }
            .mapNotNull { (inboxPath, decisions) ->
                if (inboxPath == null || !inboxPath.startsWith("$rootPath/", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val checkpointPath = "$inboxPath/Uncertain"
                ensureDirectory(checkpointPath, "Uncertain checkpoint")
                authorization += inboxPath
                decisions.forEach { decision ->
                    operations += PlannedOperation.Move(
                        source = parseFileRef(decision.artifact.stableRef),
                        destination = FileRef.Direct(checkpointPath).child(decision.artifact.displayName),
                        reason = "Keep this unresolved file in the Downloads uncertain checkpoint for review.",
                    )
                    selectedRefs += decision.artifact.stableRef
                }
                FilingReviewGroup(
                    projectName = "Uncertain",
                    projectHomePath = inboxPath,
                    destinationPath = checkpointPath,
                    existingProjectHome = true,
                    release = null,
                    items = decisions.map { reviewItem(it).copy(destinationPath = checkpointPath) },
                    editableDirectDestination = false,
                    isUncertainCheckpoint = true,
                )
            }

        return InboxFilingPlan(
            operations = operations,
            authorizedDestinationRoots = authorization.map { FileRef.Direct(it) },
            defaultSelectedSourceRefs = selectedRefs,
            presentation = FilingReviewPresentation(groups, unresolved, checkpointGroups,
                retainedUncertainSourceRefs = retainedUncertainSourceRefs),
        )
    }

    private fun reviewItem(decision: FilingDecision): FilingReviewItem = FilingReviewItem(
        sourceRef = decision.artifact.stableRef,
        displayName = decision.artifact.displayName,
        sizeBytes = decision.artifact.sizeBytes,
        confidence = decision.confidence,
        evidence = decision.evidence.sortedByDescending { it.weight }.map { it.detail }.distinct().take(4),
        destinationPath = decision.destinationDirectory,
        isDirectory = decision.artifact.isDirectory,
        contentExcerpt = decision.artifact.indexedText.take(400).takeIf { it.isNotBlank() },
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
        retainedUncertainSourceRefs: Set<String> = emptySet(),
    ): InboxFilingPlan {
        require(result.decisions.map { it.artifact.stableRef }.distinct().size == result.decisions.size) { "Duplicate filing sources." }
        val operations = mutableListOf<PlannedOperation>()
        val plannedDirectories = linkedSetOf<String>()
        val selectedRefs = linkedSetOf<String>()
        val groups = mutableListOf<FilingReviewGroup>()

        val proposed = result.proposed.filter { it.projectHome != null && it.destinationDirectory != null }
        proposed.groupBy { decision ->
            val home = requireNotNull(decision.projectHome)
            home.path.trimEnd('/') to decision.destinationDirectory
        }.forEach { (_, decisions) ->
            val first = decisions.first()
            val home = requireNotNull(first.projectHome)
            val projectName = requireNotNull(first.projectName)
            val existingHomeRef = existingHomes[home.path.trimEnd('/')]
            val homeParts = if (existingHomeRef == null) {
                require(home.path.startsWith(scopeRoot.rawValue().trimEnd('/') + "/")) { "Project home is outside the selected tree." }
                home.path.removePrefix(scopeRoot.rawValue().trimEnd('/') + "/").split('/')
            } else listOf(projectName)
            require(homeParts.isNotEmpty() && homeParts.all { InboxFilingEngine.sanitizeSegment(it) == it }) { "Unsafe project folder." }
            var homeRef: FileRef = existingHomeRef ?: scopeRoot
            if (existingHomeRef == null) {
                for (part in homeParts) {
                    val child = homeRef.child(part)
                    if (plannedDirectories.add(child.rawValue())) operations += PlannedOperation.CreateDirectory(homeRef, part, "Create or reuse the reviewed project/category home inside the granted tree.")
                    homeRef = child
                }
            }

            val release = first.release
            val relativeParts = requireNotNull(first.destinationDirectory).removePrefix(home.path.trimEnd('/'))
                .trim('/').split('/').filter { it.isNotBlank() }
            require(relativeParts.all { InboxFilingEngine.sanitizeSegment(it) == it }) { "Unsafe selected-tree destination." }
            var destinationRef = homeRef
            for (part in relativeParts) {
                val child = destinationRef.child(part)
                if (plannedDirectories.add(child.rawValue())) {
                    operations += PlannedOperation.CreateDirectory(destinationRef, part, "Create or reuse the reviewed project folder.")
                }
                destinationRef = child
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
                append(scopeLabel).append(" › ").append(homeParts.joinToString(" › "))
                relativeParts.forEach { append(" › ").append(it) }
            }
            groups += FilingReviewGroup(
                projectName = projectName,
                projectHomePath = if (existingHomeRef != null) home.path else "$scopeLabel › ${homeParts.joinToString(" › ")}",
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
                        contentExcerpt = decision.artifact.indexedText.take(400).takeIf { it.isNotBlank() },
                    )
                },
                hierarchy = home.hierarchy,
                    roleFolders = home.roleFolders,
                editableDirectDestination = false,
            )
        }

        val checkpointDecisions = result.unresolved.filterNot { it.artifact.stableRef in retainedUncertainSourceRefs }
        val checkpointGroups = if (checkpointDecisions.isEmpty()) {
            emptyList()
        } else {
            val checkpointRef = scopeRoot.child("Uncertain")
            operations += PlannedOperation.CreateDirectory(
                parent = scopeRoot,
                name = "Uncertain",
                reason = "Create or reuse the uncertain checkpoint inside the granted inbox.",
            )
            checkpointDecisions.forEach { decision ->
                operations += PlannedOperation.Move(
                    source = parseFileRef(decision.artifact.stableRef),
                    destination = checkpointRef.child(decision.artifact.displayName),
                    reason = "Keep this unresolved file in the uncertain checkpoint for review.",
                )
                selectedRefs += decision.artifact.stableRef
            }
            listOf(
                FilingReviewGroup(
                    projectName = "Uncertain",
                    projectHomePath = scopeLabel,
                    destinationPath = "$scopeLabel › Uncertain",
                    existingProjectHome = true,
                    release = null,
                    items = checkpointDecisions.map { decision ->
                        FilingReviewItem(
                            sourceRef = decision.artifact.stableRef,
                            displayName = decision.artifact.displayName,
                            sizeBytes = decision.artifact.sizeBytes,
                            confidence = decision.confidence,
                            evidence = decision.evidence.sortedByDescending { it.weight }.map { it.detail }.distinct().take(4),
                            destinationPath = "$scopeLabel › Uncertain",
                        )
                    },
                    editableDirectDestination = false,
                    isUncertainCheckpoint = true,
                ),
            )
        }

        return InboxFilingPlan(
            operations = operations,
            authorizedDestinationRoots = emptyList(),
            defaultSelectedSourceRefs = selectedRefs,
            presentation = FilingReviewPresentation(
                groups = groups.sortedWith(compareBy<FilingReviewGroup> { it.projectName.lowercase() }.thenBy { it.release.orEmpty() }),
                unresolved = (result.unresolved + result.proposed.filter { it.projectHome == null }).map { decision ->
                    FilingReviewItem(
                        sourceRef = decision.artifact.stableRef,
                        displayName = decision.artifact.displayName,
                        sizeBytes = decision.artifact.sizeBytes,
                        confidence = decision.confidence,
                        evidence = decision.evidence.sortedByDescending { it.weight }.map { it.detail }.distinct().take(4),
                        destinationPath = null,
                        contentExcerpt = decision.artifact.indexedText.take(400).takeIf { it.isNotBlank() },
                    )
                },
                checkpointGroups = checkpointGroups,
                retainedUncertainSourceRefs = retainedUncertainSourceRefs,
            ),
        )
    }
}
