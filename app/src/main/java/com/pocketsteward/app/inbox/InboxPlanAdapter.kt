package com.pocketsteward.app.inbox

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.child

/** Result of translating semantic filing decisions into the ordinary mutation plan language. */
data class InboxPlanResult(
    val operations: List<PlannedOperation>,
    val authorizedDestinationRoots: List<FileRef.Direct>,
    val filingHints: Map<String, FilingUiHint>,
    val strongSourceRefs: Set<String>,
    val notes: List<String>,
    val projectHomesToRemember: List<com.pocketsteward.app.saved.ProjectHome>,
)

object InboxPlanAdapter {
    fun build(
        analysis: InboxFilingAnalysis,
        storageRootPath: String,
        existingDirectories: Set<String>,
        protectedDirectories: Set<String> = emptySet(),
    ): InboxPlanResult {
        val normalizedExisting = existingDirectories.mapTo(linkedSetOf(), ::normPath)
        val normalizedProtected = protectedDirectories.mapTo(linkedSetOf(), ::normPath)
        val storageRoot = storageRootPath.trimEnd('/')
        val operations = mutableListOf<PlannedOperation>()
        val authorizedRoots = linkedSetOf<FileRef.Direct>()
        val hints = linkedMapOf<String, FilingUiHint>()
        val strongRefs = linkedSetOf<String>()
        val plannedDirectories = linkedSetOf<String>()
        val rememberedHomes = linkedMapOf<String, com.pocketsteward.app.saved.ProjectHome>()
        val notes = mutableListOf<String>()

        for (decision in analysis.decisions.sortedWith(
            compareBy<FilingDecision> { it.projectHome.path.lowercase() }
                .thenBy { it.release.orEmpty() }
                .thenBy { it.artifact.displayName.lowercase() },
        )) {
            val homePath = decision.projectHome.path.trimEnd('/')
            if (!isWithin(storageRoot, homePath) || homePath == storageRoot) {
                notes += "${decision.artifact.displayName}: proposed project home is outside the authorized storage root."
                continue
            }

            val homeName = homePath.substringAfterLast('/')
            if (sanitizeSegment(homeName) == null) {
                notes += "${decision.artifact.displayName}: project home name is unsafe."
                continue
            }

            val extraSegment = when (decision.projectHome.hierarchy) {
                ProjectHierarchy.FLAT -> null
                ProjectHierarchy.VERSIONED -> sanitizeSegment(decision.release)
                ProjectHierarchy.CATEGORY -> categoryFor(decision.artifact.extension)
            }
            val finalDirectory = if (extraSegment == null) homePath else "$homePath/$extraSegment"

            if (protectedByAncestor(homePath, finalDirectory, normalizedProtected)) {
                notes += "${decision.artifact.displayName}: ${decision.projectHome.name} is protected and was left untouched."
                continue
            }

            val homeExists = normPath(homePath) in normalizedExisting
            val finalExists = normPath(finalDirectory) in normalizedExisting

            if (!homeExists && normPath(homePath) !in plannedDirectories) {
                val parentPath = homePath.substringBeforeLast('/', missingDelimiterValue = "")
                if (parentPath.isBlank() || normPath(parentPath) !in normalizedExisting) {
                    notes += "${decision.artifact.displayName}: parent of proposed project home does not exist."
                    continue
                }
                plannedDirectories += normPath(homePath)
                operations += PlannedOperation.CreateDirectory(
                    parent = FileRef.Direct(parentPath),
                    name = homeName,
                    reason = "Create approved project home for ${decision.projectHome.name}.",
                )
                authorizedRoots += FileRef.Direct(parentPath)
            }

            if (extraSegment != null && !finalExists && plannedDirectories.add(normPath(finalDirectory))) {
                operations += PlannedOperation.CreateDirectory(
                    parent = FileRef.Direct(homePath),
                    name = extraSegment,
                    reason = when (decision.projectHome.hierarchy) {
                        ProjectHierarchy.VERSIONED -> "Create release folder $extraSegment for ${decision.projectHome.name}."
                        ProjectHierarchy.CATEGORY -> "Create ${decision.projectHome.name} category folder $extraSegment."
                        ProjectHierarchy.FLAT -> error("Flat hierarchy has no extra segment")
                    },
                )
                if (homeExists) authorizedRoots += FileRef.Direct(homePath)
            }

            if (homeExists && finalExists) {
                authorizedRoots += FileRef.Direct(finalDirectory)
            } else if (homeExists) {
                authorizedRoots += FileRef.Direct(homePath)
            }

            val destination = FileRef.Direct(finalDirectory).child(decision.artifact.displayName)
            val reason = buildString {
                append("File with ${decision.confidence.name.lowercase()} project confidence: ")
                append(decision.evidence.joinToString("; ") { it.detail })
            }
            operations += PlannedOperation.Move(
                source = FileRef.Direct(decision.artifact.stableRef),
                destination = destination,
                reason = reason,
            )

            if (decision.confidence == FilingConfidence.STRONG) {
                strongRefs += decision.artifact.stableRef
            }
            hints[decision.artifact.stableRef] = FilingUiHint(
                sourceRef = decision.artifact.stableRef,
                projectName = decision.projectHome.name,
                projectHomePath = homePath,
                release = decision.release,
                destinationPath = finalDirectory,
                confidence = decision.confidence,
                evidence = decision.evidence.map { it.detail },
                sizeBytes = decision.artifact.sizeBytes,
                packageId = decision.artifact.apkPackageName,
                homeAlreadyExisted = homeExists,
                releaseFolderAlreadyExisted = finalExists,
            )
            rememberedHomes[normPath(homePath)] = decision.projectHome.copy(
                path = homePath,
                aliases = (decision.projectHome.aliases +
                    decision.projectHome.name +
                    InboxFilingEngine.inferProjectStem(decision.artifact.displayName).orEmpty())
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() },
                packageIds = (decision.projectHome.packageIds + listOfNotNull(decision.artifact.apkPackageName))
                    .distinctBy { it.lowercase() },
            )
        }

        if (analysis.unresolved.isNotEmpty()) {
            notes += "${analysis.unresolved.size} unresolved file(s) stay in the inbox."
            analysis.unresolved.take(8).forEach { unresolved ->
                notes += "${unresolved.artifact.displayName}: ${unresolved.reason}"
            }
            if (analysis.unresolved.size > 8) {
                notes += "${analysis.unresolved.size - 8} more unresolved file(s) were left in place."
            }
        }

        val probableCount = hints.values.count { it.confidence == FilingConfidence.PROBABLE }
        if (probableCount > 0) {
            notes += "$probableCount probable match(es) are shown for review and left unchecked by default."
        }

        return InboxPlanResult(
            operations = operations,
            authorizedDestinationRoots = authorizedRoots.distinctBy { normPath(it.absolutePath) },
            filingHints = hints,
            strongSourceRefs = strongRefs,
            notes = notes.distinct(),
            projectHomesToRemember = rememberedHomes.values.toList(),
        )
    }

    fun sanitizeSegment(value: String?): String? {
        val cleaned = value
            ?.trim()
            ?.trimEnd('.', ' ')
            ?.take(80)
            ?.trim()
            ?: return null
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return null
        if ('/' in cleaned || '\\' in cleaned || cleaned.any(Char::isISOControl)) return null
        return cleaned
    }

    private fun categoryFor(extension: String): String = when (extension.lowercase()) {
        "apk", "aab" -> "Builds"
        "zip", "7z", "rar", "tar", "gz" -> "Archives"
        "md", "txt", "pdf", "doc", "docx", "rtf" -> "Notes"
        "png", "jpg", "jpeg", "webp", "gif", "svg" -> "Images"
        else -> "Files"
    }

    private fun protectedByAncestor(home: String, destination: String, protected: Set<String>): Boolean {
        val homeNorm = normPath(home)
        val destNorm = normPath(destination)
        return protected.any { protectedPath ->
            homeNorm == protectedPath || homeNorm.startsWith("$protectedPath/") ||
                destNorm == protectedPath || destNorm.startsWith("$protectedPath/")
        }
    }

    private fun isWithin(root: String, path: String): Boolean {
        val r = normPath(root)
        val p = normPath(path)
        return p.startsWith("$r/")
    }

    private fun normPath(value: String): String = value.trim().trimEnd('/').lowercase()
}
