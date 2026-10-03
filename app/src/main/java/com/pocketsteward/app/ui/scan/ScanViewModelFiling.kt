package com.pocketsteward.app.ui.scan

import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.content.index.ContentIndexCandidate
import com.pocketsteward.app.content.index.IndexedExtractionStatus
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.executor.StorageDigest
import com.pocketsteward.app.filing.FilingContinuationPolicy
import com.pocketsteward.app.filing.FilingSourceAvailability
import com.pocketsteward.app.image.ImageReviewBatch
import com.pocketsteward.app.image.ImageReviewCoverage
import com.pocketsteward.app.image.ImageReviewResult
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.filing.FilingConfidence
import com.pocketsteward.app.filing.FilingEvidence
import com.pocketsteward.app.filing.FilingEvidenceKind
import com.pocketsteward.app.filing.InboxFilingIntake
import com.pocketsteward.app.filing.InboxFilingResult
import com.pocketsteward.app.filing.InboxFilingEngine
import com.pocketsteward.app.filing.InboxFilingPlanAdapter
import com.pocketsteward.app.filing.InboxFilingSafPlanAdapter
import com.pocketsteward.app.filing.ProjectHomeCandidate
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.DirectProtection
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.storage.child
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GENERIC_TOP_LEVEL_FOLDERS = setOf(
    "android", "download", "downloads", "documents", "pictures", "movies", "music",
    "dcim", "alarms", "notifications", "ringtones", "podcasts", "audiobooks",
    "pocketsteward", "trash", "uncertain",
)

/**
 * Files loose incoming artifacts into durable project homes. This is separate
 * from Smart cleanup: the source root is an inbox and the destination may be
 * elsewhere in authorized shared storage.
 */
internal data class FilingContinuation(val preview: ScanUiState.PlanPreview, val session: FilingSession, val retryUnavailable: Boolean = false) {
    val sourceRefs = session.result.decisions.mapTo(hashSetOf()) { it.artifact.stableRef }
    val assignments = session.manualAssignments ?: session.result.decisions.filter { decision ->
        decision.evidence.any { it.kind == FilingEvidenceKind.USER_MAPPING }
    }.associateBy { it.artifact.stableRef }
    val originalSources = session.originalSources.orEmpty() + preview.reviewedSources
}

internal fun ScanViewModel.proposeInboxFiling(summary: ScanUiState.Summary, checkpointOnly: Boolean = false, scheduled: com.pocketsteward.app.scheduled.PendingCleanupSuggestion? = null, continuation: FilingContinuation? = null) {
    filingPlanningJob?.cancel()
    filingPlanningJob = viewModelScope.launch {
        _uiState.value = ScanUiState.Working(
            label = "Planning inbox filing",
            detail = "Matching incoming artifacts to projects and releases",
        )

        try {
            val mode = settingsRepository.storageAccessState.first().mode
                ?: error("No storage access mode is active.")
            val allowlist = continuation?.sourceRefs ?: scheduled?.newFileRefs?.toHashSet()
            val extraNotes = scheduled?.let { listOf("Scheduled filing considers only ${it.newFileRefs.size} saved new-file references that are still direct inbox children. Older files and nested folder contents are left alone.") }.orEmpty()
            if (mode == StorageAccessMode.SAF) {
                proposeInboxFilingSaf(summary, checkpointOnly, allowlist, extraNotes, continuation)
                if (scheduled != null && _uiState.value is ScanUiState.PlanPreview) settingsRepository.clearPendingCleanupSuggestion()
                return@launch
            }

            val sourceRootKeys = summary.scopes.map { it.root.rawValue().trimEnd('/') }.toSet()
            val scannedRecords = allRecordsForScopes(summary.scopes)
            val intake = InboxFilingIntake.select(
                scannedRecords, sourceRootKeys, checkpointOnly, includeDirectories = true,
                checkpointRootRefs = withContext(Dispatchers.IO) {
                    summary.scopes.mapNotNull { scope ->
                        val info = container.gatewayFor(mode).stat(scope.root)
                        scope.root.rawValue().takeIf { info.isDirectory && info.displayName.equals("Uncertain", true) }
                    }.toSet()
                },
            )
            val records = intake.records.filter { allowlist == null || ((continuation != null || !it.isDirectory) && it.stableRef in allowlist) }
            val skippedFolders = 0

            if (records.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    if (checkpointOnly) "No files are waiting in an Uncertain checkpoint under the configured inboxes."
                    else "No loose files are waiting in the selected inbox. $skippedFolders existing folder(s) were left alone.",
                )
                return@launch
            }

            val metadataEnabled = workflowPrivacy().metadataIndexingEnabled
            val enriched = withContext(Dispatchers.IO) {
                records.map { record ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val metadata = enrichFreshFilingRecord(record, summary.mode, metadataEnabled)
                    if (metadata.changed) container.database.fileRecordDao().upsert(metadata.record)
                    record.stableRef to metadata
                }.toMap()
            }

            val intakeSnapshot = continuation?.preview?.filingPresentation?.intakeSnapshot ?: withContext(Dispatchers.IO) {
                com.pocketsteward.app.filing.FilingTaskInventoryBuilder.captureIntake(
                    container.gatewayFor(mode), summary.scopes.map { it.root.rawValue() } + records.mapNotNull { it.parentRef },
                )
            }
            val freshRecords = records.map { enriched.getValue(it.stableRef).record }
            val snapshots = filingOriginalSources(freshRecords.filter { enriched[it.stableRef]?.sourceError == null }, summary.mode, continuation)
            val unavailableSources = enriched.mapNotNull { (ref, metadata) -> metadata.sourceError?.let { ref to it } }.toMap() + snapshots.errors
            val readableRecords = freshRecords.filter { it.stableRef !in unavailableSources }
            val imageEvidence = prepareFilingImages(readableRecords, continuation?.retryUnavailable == true)
            val content = prepareFilingContent(readableRecords, summary)
            val indexedText = content.text

            @Suppress("DEPRECATION")
            val storageRoot = FileRef.Direct(Environment.getExternalStorageDirectory().absolutePath.trimEnd('/'))
            val gateway = container.gatewayFor(StorageAccessMode.DIRECT)
            val topLevel = withContext(Dispatchers.IO) {
                gateway.listChildren(storageRoot).filter { it.isDirectory }
            }
            val topLevelDirectories = topLevel.map { it.ref.rawValue().trimEnd('/') }.toSet()

            val savedHomes = settingsRepository.projectHomes.first().mapNotNull { home ->
                val exists = withContext(Dispatchers.IO) {
                    gateway.exists(FileRef.Direct(home.path)) &&
                        runCatching { gateway.stat(FileRef.Direct(home.path)).isDirectory }.getOrDefault(false)
                }
                if (!exists) null else ProjectHomeCandidate(
                    name = home.name,
                    path = home.path.trimEnd('/'),
                    aliases = home.aliases,
                    packageIds = home.packageIds,
                    hierarchy = home.hierarchy,
                    roleFolders = home.roleFolders,
                    persisted = true,
                )
            }

            val favorites = settingsRepository.favoriteDestinations.first().mapNotNull { favorite ->
                val exists = withContext(Dispatchers.IO) {
                    gateway.exists(FileRef.Direct(favorite.path)) &&
                        runCatching { gateway.stat(FileRef.Direct(favorite.path)).isDirectory }.getOrDefault(false)
                }
                if (!exists) null else ProjectHomeCandidate(
                    name = favorite.name,
                    path = favorite.path.trimEnd('/'),
                    aliases = listOf(favorite.name),
                    hierarchy = ProjectHierarchyStrategy.VERSIONED,
                    persisted = true,
                )
            }

            val discovered = topLevel.mapNotNull { entry ->
                val name = entry.displayName.trim()
                if (name.lowercase() in GENERIC_TOP_LEVEL_FOLDERS || name.startsWith('.')) return@mapNotNull null
                ProjectHomeCandidate(
                    name = name,
                    path = entry.ref.rawValue().trimEnd('/'),
                    aliases = listOf(name),
                    hierarchy = ProjectHierarchyStrategy.VERSIONED,
                    persisted = false,
                )
            }
            val documentProjects = topLevel.firstOrNull { it.displayName.equals("Documents", ignoreCase = true) }
                ?.let { documents ->
                    withContext(Dispatchers.IO) {
                        runCatching { gateway.listChildren(documents.ref) }.getOrDefault(emptyList())
                    }.filter { it.isDirectory && !it.displayName.startsWith('.') }
                        .map { entry ->
                            ProjectHomeCandidate(
                                name = entry.displayName,
                                path = entry.ref.rawValue().trimEnd('/'),
                                aliases = listOf(entry.displayName),
                                hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES,
                                persisted = false,
                            )
                        }
                }.orEmpty()

            val knowledge = withContext(Dispatchers.IO) {
                container.projectKnowledge.discover(storageRoot, (settingsRepository.inboxRoots.first().map { it.path } + sourceRootKeys).distinct(),
                    roleProfiles = listOf(settingsRepository.hierarchyTemplate.first().roleFolders) +
                        settingsRepository.namedHierarchyTemplates.first().map { it.template.roleFolders } + settingsRepository.projectHomes.first().map { it.roleFolders },
                    onProgress = { done -> _uiState.value = ScanUiState.Working("Discovering project homes", "$done indexed layouts checked; existing role folders are verified live", processed = done) })
            }
            val observedHomes = (knowledge.homes + documentProjects).distinctBy { it.path.lowercase() }

            val artifacts = records.map { record ->
                val metadata = enriched.getValue(record.stableRef)
                val updated = metadata.record
                FilingArtifact(
                    stableRef = updated.stableRef,
                    displayName = updated.displayName,
                    extension = updated.extension,
                    sizeBytes = updated.sizeBytes,
                    createdAt = updated.createdAt,
                    modifiedAt = updated.modifiedAt,
                    parentRef = updated.parentRef,
                    apkPackageName = updated.apkPackageName.takeIf { metadataEnabled },
                    apkVersionName = updated.apkVersionName.takeIf { metadataEnabled },
                    apkLabel = metadata.apkLabel,
                    apkVersionCode = metadata.apkVersionCode,
                    archiveSample = metadata.archiveSample,
            captureDate = metadata.captureDate, mediaArtist = metadata.mediaArtist, mediaAlbum = metadata.mediaAlbum,
                    imageLabels = imageEvidence.evidence[updated.stableRef]?.let { insight -> insight.labels.map { it.label } + if (insight.likelyScreenshot) listOf("screenshot") else emptyList() }.orEmpty(),
                    imageText = imageEvidence.evidence[updated.stableRef]?.detectedText.orEmpty(),
                    indexedText = indexedText[updated.stableRef].orEmpty(),
                    isDirectory = updated.isDirectory,
                )
            }

            val folderMaterial = prepareFilingFolderEvidence(scannedRecords, readableRecords, snapshots.baselines, summary)
            val workflowRoots = summary.scopes.associate { it.root.rawValue() to workflowDestination(it, summary.mode)?.rawValue() }
            val preferredRoots = artifacts.mapNotNull { artifact ->
                workflowRoots[sourceRootFor(artifact.stableRef, summary.scopes) ?: summary.scopes.first().root.rawValue()]?.let { artifact.stableRef to it }
            }.toMap()
            val childRoots = folderMaterial.ownerByChild.mapNotNull { (child, owner) -> preferredRoots[owner]?.let { child to it } }.toMap()
            val inferred = withContext(Dispatchers.Default) {
                val context = kotlinx.coroutines.currentCoroutineContext()
                val keywords = settingsRepository.projectKeywords.first()
                val corrections = settingsRepository.correctionRules.first()
                val roles = settingsRepository.hierarchyTemplate.first().roleFolders
                val topics = settingsRepository.documentTopicRules.first()
                val children = InboxFilingEngine.resolve(folderMaterial.children, (savedHomes + favorites).distinctBy { it.path.lowercase() },
                    observedHomes + discovered, keywords, corrections, storageRoot.absolutePath, roles, childRoots, documentTopics = topics, checkCancelled = { context.ensureActive() })
                val resolved = InboxFilingEngine.resolve(
                    artifacts = artifacts,
                    persistedHomes = (savedHomes + favorites).distinctBy { it.path.lowercase() },
                    discoveredHomes = observedHomes + discovered,
                    projectKeywords = settingsRepository.projectKeywords.first(),
                    correctionRules = settingsRepository.correctionRules.first(),
                    storageRoot = storageRoot.absolutePath,
                    newProjectRoles = roles,
                    newProjectRoots = preferredRoots,
                    documentTopics = topics,
                    checkCancelled = { context.ensureActive() },
                )
                com.pocketsteward.app.filing.FilingFolderEvidence.reconcile(resolved, children, folderMaterial.ownerByChild,
                    folderMaterial.entryCounts, snapshots.baselines.filterValues { it.directoryDigest != null }.mapValues { it.value.directoryEntryCount })
            }
            // Folder contents stay together; their internal layout is never split by role.
            val existingDirectories = linkedSetOf<String>()
            existingDirectories += topLevelDirectories
            existingDirectories += scannedRecords.filter { record ->
                record.isDirectory && record.parentRef?.trimEnd('/') in sourceRootKeys
            }.map { it.stableRef.trimEnd('/') }
            existingDirectories += observedHomes.map { it.path }
            existingDirectories += savedHomes.map { it.path }
            existingDirectories += favorites.map { it.path }
            val releases = withContext(Dispatchers.IO) {
                com.pocketsteward.app.filing.FilingReleaseDiscovery.discover(inferred, gateway) { done, total ->
                    _uiState.value = ScanUiState.Working("Checking release layouts", "$done of $total relevant project homes checked", processed = done)
                }
            }
            existingDirectories += releases.directories

            val conventional = com.pocketsteward.app.filing.FilingReleaseConvention.reconcile(inferred, existingDirectories, releases.unavailableHomes)
            val choices = FilingSourceAvailability.block(FilingContinuationPolicy.applyAssignments(conventional, continuation?.assignments.orEmpty()), unavailableSources + folderMaterial.errors)
            val result = resolveDirectDestinationCollisions(choices, gateway, storageRoot.absolutePath)
            val plan = withContext(Dispatchers.Default) {
                InboxFilingPlanAdapter.build(
                    result = result,
                    storageRoot = storageRoot,
                    existingDirectories = existingDirectories,
                    retainedUncertainSourceRefs = intake.retainedUncertainSourceRefs,
                )
            }

            val originalSources = snapshots.baselines
            val nextSession = FilingSession(result, storageRoot, existingDirectories, intake.retainedUncertainSourceRefs,
                (savedHomes + favorites + observedHomes + discovered + continuation?.session?.homes.orEmpty()).distinctBy { it.path.lowercase() },
                manualAssignments = continuation?.assignments.orEmpty(), reviewId = java.util.UUID.randomUUID().toString(), originalSources = originalSources)

            val notes = buildList {
                add("The selected landing folder is being treated as an inbox, not a permanent category tree.")
                add("Project ownership outranks file type. APK, ZIP, notes, and supporting assets can travel together when their evidence agrees.")
                add(if (checkpointOnly) "Strong matches are selected by default. Probable matches wait for review. Anything still unresolved stays in its existing Uncertain checkpoint."
                    else "Strong matches are selected by default. Probable matches are proposed but left unchecked. Unresolved files and intact folders move to Uncertain after review.")
                add(content.summary)
                if (folderMaterial.entryCounts.isNotEmpty()) add(folderMaterial.summary)
                add(imageEvidence.coverage.summary)
                add(knowledge.explanation)
                addAll(extraNotes)
                if (!metadataEnabled) add("Metadata inspection is off; camera dates, media tags and archive entries were not read.")
                val partialArchives = enriched.values.count { it.archiveComplete == false }
                if (partialArchives > 0) add("$partialArchives archives had partial or unavailable inspection. Only observed entry names were used as evidence.")
                add("Existing project homes are preferred over creating near-duplicate folders.")
                add("Existing folders move intact. Their contents are checked again before execution; changed folders require a fresh review.")
                if (skippedFolders > 0) {
                    add("$skippedFolders existing folder(s) remain in the inbox; this pass does not move folder contents without a reviewed snapshot.")
                }
            }

            showPlanPreview(
                goal = if (checkpointOnly) "Sort Uncertain" else "Inbox filing",
                operations = plan.operations,
                scopes = summary.scopes,
                scopeNotes = notes,
                authorizedDestinationRoots = plan.authorizedDestinationRoots,
                defaultSelectedSourceRefs = continuationSelections(plan.defaultSelectedSourceRefs, continuation),
                filingPresentation = plan.presentation.copy(skippedInboxFolders = skippedFolders, reviewingUncertain = checkpointOnly, imageCoverage = imageEvidence.coverage, reviewSessionId = nextSession.reviewId,
                    heldSourceRefs = continuation?.preview?.filingPresentation?.heldSourceRefs,
                    indexedFolderDescendantCount = intake.indexedFolderDescendantCount, intakeSnapshot = intakeSnapshot),
                previousReviewedSources = originalSources,
                preserveWorkflowSelection = continuation != null,
                pendingCorrections = continuation?.preview?.pendingCorrections.orEmpty(),
            )
            filingSession = nextSession
            if (scheduled != null && _uiState.value is ScanUiState.PlanPreview) settingsRepository.clearPendingCleanupSuggestion()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}


private suspend fun ScanViewModel.proposeInboxFilingSaf(summary: ScanUiState.Summary, checkpointOnly: Boolean, allowlist: Set<String>?, extraNotes: List<String>, continuation: FilingContinuation?) {
    val scope = summary.scopes.singleOrNull() ?: run {
        _uiState.value = ScanUiState.Error(
            "Selected-folder filing currently needs one granted tree at a time.",
        )
        return
    }
    val grantedRoot = withContext(Dispatchers.IO) { container.gatewayFor(StorageAccessMode.SAF).stat(scope.root) }
    require(!grantedRoot.displayName.equals("Uncertain", true)) {
        "This grant covers only Uncertain, so files cannot leave that checkpoint. Use full file access or grant an accessible parent folder, then rebuild the review."
    }
    val rootKey = scope.root.rawValue().trimEnd('/')
    val scannedRecords = allRecordsForScopes(summary.scopes)
    val skippedFolders = scannedRecords.count { record ->
        record.isDirectory && record.parentRef?.trimEnd('/') == rootKey &&
            !record.displayName.equals("Uncertain", ignoreCase = true)
    }
    val intake = InboxFilingIntake.select(
        scannedRecords, setOf(rootKey), checkpointOnly, includeDirectories = false,
    )
    val records = intake.records.filter { allowlist == null || it.stableRef in allowlist }
    if (records.isEmpty()) {
        _uiState.value = ScanUiState.Error(
            if (checkpointOnly) "No files are waiting in an Uncertain checkpoint inside the granted inbox."
            else "No loose files are waiting at the top of the granted inbox. $skippedFolders existing folder(s) were left alone.",
        )
        return
    }

    val metadataEnabled = workflowPrivacy().metadataIndexingEnabled
    val enriched = withContext(Dispatchers.IO) {
        records.map { record ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val metadata = enrichFreshFilingRecord(record, summary.mode, metadataEnabled)
            if (metadata.changed) container.database.fileRecordDao().upsert(metadata.record)
            record.stableRef to metadata
        }.toMap()
    }
    val intakeSnapshot = continuation?.preview?.filingPresentation?.intakeSnapshot ?: withContext(Dispatchers.IO) {
        com.pocketsteward.app.filing.FilingTaskInventoryBuilder.captureIntake(
            container.gatewayFor(StorageAccessMode.SAF), summary.scopes.map { it.root.rawValue() } + records.mapNotNull { it.parentRef },
        )
    }
    val freshRecords = records.map { enriched.getValue(it.stableRef).record }
    val snapshots = filingOriginalSources(freshRecords.filter { enriched[it.stableRef]?.sourceError == null }, summary.mode, continuation)
    val unavailableSources = enriched.mapNotNull { (ref, metadata) -> metadata.sourceError?.let { ref to it } }.toMap() + snapshots.errors
    val readableRecords = freshRecords.filter { it.stableRef !in unavailableSources }
    val imageEvidence = prepareFilingImages(readableRecords, continuation?.retryUnavailable == true)
    val content = prepareFilingContent(readableRecords, summary)
    val indexedText = content.text

    val gateway = container.gatewayFor(StorageAccessMode.SAF)
    val children = withContext(Dispatchers.IO) {
        runCatching { gateway.listChildren(scope.root) }.getOrDefault(emptyList())
    }
    val homes = children.filter { it.isDirectory }
        .filterNot { it.displayName.startsWith('.') || it.displayName.lowercase() in GENERIC_TOP_LEVEL_FOLDERS }
        .map { entry ->
            ProjectHomeCandidate(
                name = entry.displayName,
                path = entry.ref.rawValue().trimEnd('/'),
                aliases = listOf(entry.displayName),
                hierarchy = ProjectHierarchyStrategy.VERSIONED,
                persisted = false,
            )
        }
    val artifacts = records.map { record ->
        val metadata = enriched.getValue(record.stableRef)
        val updated = metadata.record
        FilingArtifact(
            stableRef = updated.stableRef,
            displayName = updated.displayName,
            extension = updated.extension,
            sizeBytes = updated.sizeBytes,
            createdAt = updated.createdAt,
            modifiedAt = updated.modifiedAt,
            parentRef = updated.parentRef,
            apkPackageName = updated.apkPackageName.takeIf { metadataEnabled },
            apkVersionName = updated.apkVersionName.takeIf { metadataEnabled },
            apkLabel = metadata.apkLabel,
            apkVersionCode = metadata.apkVersionCode,
            archiveSample = metadata.archiveSample,
            captureDate = metadata.captureDate, mediaArtist = metadata.mediaArtist, mediaAlbum = metadata.mediaAlbum,
            imageLabels = imageEvidence.evidence[updated.stableRef]?.let { insight -> insight.labels.map { it.label } + if (insight.likelyScreenshot) listOf("screenshot") else emptyList() }.orEmpty(),
                    imageText = imageEvidence.evidence[updated.stableRef]?.detectedText.orEmpty(),
            indexedText = indexedText[updated.stableRef].orEmpty(),
        )
    }
    val workflowHomeRoot = workflowDestination(scope, summary.mode)
    val inferred = withContext(Dispatchers.Default) {
        val context = kotlinx.coroutines.currentCoroutineContext()
        InboxFilingEngine.resolve(
            artifacts = artifacts,
            persistedHomes = emptyList(),
            discoveredHomes = homes,
            projectKeywords = settingsRepository.projectKeywords.first(),
            correctionRules = settingsRepository.correctionRules.first(),
            storageRoot = scope.root.rawValue(),
            newProjectRoles = settingsRepository.hierarchyTemplate.first().roleFolders,
            newProjectRoots = artifacts.associate { it.stableRef to (workflowHomeRoot?.rawValue() ?: scope.root.rawValue()) },
            documentTopics = settingsRepository.documentTopicRules.first(),
            newTopicRoots = artifacts.associate { it.stableRef to (workflowHomeRoot ?: scope.root.child("Documents")).rawValue() },
            checkCancelled = { context.ensureActive() },
        )
    }
    val result = FilingSourceAvailability.block(FilingContinuationPolicy.applyAssignments(inferred, continuation?.assignments.orEmpty()), unavailableSources)
    val existingHomeRefs = homes.associate { home ->
        home.path to requireNotNull(children.firstOrNull { it.ref.rawValue().trimEnd('/') == home.path }?.ref)
    }
    val plan = withContext(Dispatchers.Default) {
        InboxFilingSafPlanAdapter.build(
            result = result,
            scopeRoot = scope.root,
            existingHomes = existingHomeRefs,
            scopeLabel = scope.label,
            retainedUncertainSourceRefs = intake.retainedUncertainSourceRefs,
            newHomeRoot = workflowHomeRoot ?: scope.root,
        )
    }
    val originalSources = snapshots.baselines
    val nextSession = FilingSession(result, scope.root, emptySet(), intake.retainedUncertainSourceRefs,
        (homes + continuation?.session?.homes.orEmpty()).distinctBy { it.path }, existingHomeRefs,
        manualAssignments = continuation?.assignments.orEmpty(), reviewId = java.util.UUID.randomUUID().toString(), originalSources = originalSources,
        newHomeRoot = workflowHomeRoot ?: scope.root)
    showPlanPreview(
        goal = if (checkpointOnly) "Sort Uncertain" else "Inbox filing",
        operations = plan.operations,
        scopes = summary.scopes,
        scopeNotes = listOf(
            content.summary,
            imageEvidence.coverage.summary,
            "Selected-folder access can file only inside this granted tree; it cannot discover project homes elsewhere on the device.",
            if (checkpointOnly) "Strong matches are selected by default. Probable matches wait for review. Unresolved files stay in their existing checkpoint."
            else "Strong matches are selected by default. Probable matches wait for review. Unresolved loose files move to Uncertain after review.",
            "$skippedFolders existing folder(s) remain in the inbox; this pass does not move folder contents without a reviewed snapshot.",
        ) + extraNotes + listOfNotNull(
            if (!metadataEnabled) "Metadata inspection is off; camera dates, media tags and archive entries were not read." else null,
            enriched.values.count { it.archiveComplete == false }.takeIf { it > 0 }?.let { "$it archives had partial or unavailable inspection. Only observed entry names were used as evidence." },
        ),
        defaultSelectedSourceRefs = continuationSelections(plan.defaultSelectedSourceRefs, continuation),
        filingPresentation = plan.presentation.copy(skippedInboxFolders = skippedFolders, reviewingUncertain = checkpointOnly, imageCoverage = imageEvidence.coverage, reviewSessionId = nextSession.reviewId,
            heldSourceRefs = continuation?.preview?.filingPresentation?.heldSourceRefs,
            indexedFolderDescendantCount = intake.indexedFolderDescendantCount, intakeSnapshot = intakeSnapshot),
        previousReviewedSources = originalSources,
        preserveWorkflowSelection = continuation != null,
        pendingCorrections = continuation?.preview?.pendingCorrections.orEmpty(),
    )
    filingSession = nextSession
}


private suspend fun resolveDirectDestinationCollisions(
    result: InboxFilingResult,
    gateway: com.pocketsteward.app.storage.StorageGateway,
    storageRoot: String,
): InboxFilingResult = withContext(Dispatchers.IO) {
    InboxFilingResult(
        result.decisions.map { decision ->
            val directory = decision.destinationDirectory
            if (decision.confidence == FilingConfidence.UNRESOLVED || directory == null) {
                decision
            } else {
                val homePath = decision.projectHome?.path?.trimEnd('/')
                val homeExists = homePath != null && runCatching { gateway.exists(FileRef.Direct(homePath)) }.getOrDefault(false)
                val protectionProbe = when {
                    homePath == null -> null
                    homeExists -> "$homePath/.pocket-steward-filing-probe"
                    else -> homePath
                }
                val protectionReason = protectionProbe?.let { DirectProtection.refusalDestination(storageRoot, it) }
                if (protectionReason != null) {
                    decision.copy(
                        destinationDirectory = null,
                        confidence = FilingConfidence.UNRESOLVED,
                        evidence = decision.evidence + FilingEvidence(
                            FilingEvidenceKind.DESTINATION_PROTECTED,
                            protectionReason,
                            220,
                        ),
                    )
                } else {
                    val destination = FileRef.Direct(directory).child(decision.artifact.displayName)
                    if (!runCatching { gateway.exists(destination) }.getOrDefault(false)) {
                        decision
                    } else {
                        val sameContent = runCatching {
                            val destinationMeta = gateway.stat(destination)
                            if (destinationMeta.isDirectory || destinationMeta.sizeBytes != decision.artifact.sizeBytes) {
                                false
                            } else {
                                val source = com.pocketsteward.app.storage.parseFileRef(decision.artifact.stableRef)
                                StorageDigest.sha256(gateway, source) == StorageDigest.sha256(gateway, destination)
                            }
                        }.getOrDefault(false)
                        decision.copy(
                            destinationDirectory = null,
                            confidence = FilingConfidence.UNRESOLVED,
                            evidence = decision.evidence + FilingEvidence(
                                kind = if (sameContent) FilingEvidenceKind.DESTINATION_DUPLICATE else FilingEvidenceKind.DESTINATION_CONFLICT,
                                detail = if (sameContent) {
                                    "identical file already exists in the proposed destination; source left in inbox"
                                } else {
                                    "destination already contains a different item with this name; source left in inbox"
                                },
                                weight = 200,
                            ),
                        )
                    }
                }
            }
        },
    )
}

private data class FilingContent(val text: Map<String, String>, val reused: Int, val extracted: Int, val unavailable: Int, val enabled: Boolean = true, val deferredPdf: Int = 0, val partial: Int = 0) {
    val summary: String get() = if (!enabled) "Content inspection is off. Enable it in Settings for content-based filing; this review uses names and metadata." else "Local document evidence: $reused cached, $extracted freshly inspected, $unavailable unreadable or unsupported. $partial documents have partial or unverified coverage. $deferredPdf fresh PDFs were deferred by the 40-PDF review budget; rebuild to continue or run the full content index."
}

private suspend fun ScanViewModel.prepareFilingContent(records: List<FileRecord>, summary: ScanUiState.Summary): FilingContent {
    if (!workflowPrivacy().contentInspectionEnabled) return FilingContent(emptyMap(), 0, 0, 0, enabled = false)
    val repository = container.contentIndexRepository(summary.mode)
    val readable = records.filter { !it.isDirectory && ContentExtractor.supports(it.extension) }
    val cachedPdfs = withContext(Dispatchers.IO) { repository.cachedDocuments(readable.filter { it.extension.equals("pdf", true) }.map { it.stableRef }) }
    // New PDFs precede previously failed ones so repeated failures cannot starve unseen evidence.
    val candidates = readable.sortedBy { record ->
        when {
            !record.extension.equals("pdf", true) -> 0
            cachedPdfs[record.stableRef] == null -> 1
            cachedPdfs[record.stableRef]?.extractionStatus == IndexedExtractionStatus.FAILED.name -> 3
            else -> 2
        }
    }
    val text = linkedMapOf<String, String>()
    var reused = 0
    var extracted = 0
    var unavailable = 0
    var freshPdf = 0
    var deferredPdf = 0
    var partial = 0
    for ((index, record) in candidates.withIndex()) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        _uiState.value = ScanUiState.Working(
            label = "Reading local document evidence",
            detail = "${index + 1} of ${candidates.size} · ${record.displayName}",
            processed = index, total = candidates.size,
        )
        val candidate = ContentIndexCandidate(record, sourceRootFor(record.stableRef, summary.scopes) ?: summary.scopes.first().root.rawValue())
        val budget = com.pocketsteward.app.content.ContentInspectionBudget.FILING
        val reusable = !record.extension.equals("pdf", true) || withContext(Dispatchers.IO) { repository.canReuse(candidate, budget) }
        if (record.extension.equals("pdf", true) && !reusable) {
            if (freshPdf >= 40) { deferredPdf++; continue }
            freshPdf++
        }
        val inspection = withContext(Dispatchers.IO) { repository.ensureDocument(candidate, budget) }
        if (!inspection.document.coverageComplete) partial++
        if (inspection.document.extractionStatus == IndexedExtractionStatus.INDEXED.name) {
            text[record.stableRef] = withContext(Dispatchers.IO) { repository.excerpt(record.stableRef, maxChars = 2_000) }
            if (inspection.reused) reused++ else extracted++
        } else unavailable++
    }
    return FilingContent(text, reused, extracted, unavailable, deferredPdf = deferredPdf, partial = partial)
}

private suspend fun ScanViewModel.prepareFilingImages(records: List<FileRecord>, retryUnavailable: Boolean): ImageReviewResult {
    val privacy = workflowPrivacy()
    if (!privacy.imageAnalysisEnabled) return ImageReviewResult(emptyMap(),
        ImageReviewCoverage(0, 0, 0, 0, 0, 0, 0, 0, enabled = false))
    return withContext(Dispatchers.IO) {
        ImageReviewBatch(container.imageUnderstanding).inspect(records, privacy.contentInspectionEnabled, retryUnavailable) { phase, done, total, name ->
            _uiState.value = ScanUiState.Working(
                if (phase == com.pocketsteward.app.image.ImageReviewPhase.CHECKING_CACHE) "Checking saved image evidence" else "Reading local image evidence",
                "$done of $total · $name", processed = done, total = total)
        }
    }
}

private data class FilingSourceSnapshots(val baselines: Map<String, com.pocketsteward.app.plan.SourcePrecondition>, val errors: Map<String, String>)
private suspend fun ScanViewModel.filingOriginalSources(records: List<FileRecord>, mode: StorageAccessMode, continuation: FilingContinuation?): FilingSourceSnapshots =
    withContext(Dispatchers.IO) {
        val original = continuation?.originalSources.orEmpty()
        val observed = linkedMapOf<String, com.pocketsteward.app.plan.SourcePrecondition>()
        val errors = linkedMapOf<String, String>()
        for (record in records) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                observed[record.stableRef] = if (record.isDirectory) {
                    if (continuation != null) requireNotNull(original[record.stableRef]) {
                        "This older review has no initial folder snapshot. Rebuild the review before filing this folder."
                    } else com.pocketsteward.app.plan.SourcePreconditions.capture(container.gatewayFor(mode), com.pocketsteward.app.storage.parseFileRef(record.stableRef))
                } else requireNotNull(com.pocketsteward.app.plan.SourcePreconditions.from(record))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                errors[record.stableRef] = "This source could not be captured as a safe reviewed unit. Refresh its inventory or restore access before filing."
            }
        }
        FilingSourceSnapshots(observed + original, errors)
    }

private fun continuationSelections(defaults: Set<String>, continuation: FilingContinuation?): Set<String> {
    if (continuation == null) return defaults
    val preview = continuation.preview
    val previousSources = preview.accepted.mapNotNullTo(hashSetOf()) { com.pocketsteward.app.plan.ReviewedSources.sourceOf(it)?.rawValue() }
    val previousSelected = preview.selectedIndices.mapNotNullTo(hashSetOf()) { index ->
        preview.accepted.getOrNull(index)?.let(com.pocketsteward.app.plan.ReviewedSources::sourceOf)?.rawValue()
    }
    return FilingContinuationPolicy.selectedSources(defaults, previousSources, previousSelected)
}

internal fun ScanViewModel.continueFilingImageEvidence(retryUnavailable: Boolean = false) {
    if (busy.value != null || filingPlanningJob?.isActive == true || filingEditJob?.isActive == true) return
    val preview = _preview.value ?: return
    val session = filingSession ?: return
    if (!ReviewDraftPolicy.matchesFilingSession(preview, session)) return
    val filing = preview.filingPresentation ?: return
    val mode = preview.storageMode ?: return
    // Restored reviews have no in-memory scan summary. Scope and original intake remain durable.
    val summary = ScanUiState.Summary(preview.scopes, mode, session.result.decisions.size,
        session.result.decisions.sumOf { it.artifact.sizeBytes }, emptyMap())
    proposeInboxFiling(summary, filing.reviewingUncertain, continuation = FilingContinuation(preview, session, retryUnavailable))
}

private suspend fun ScanViewModel.enrichFreshFilingRecord(record: FileRecord, mode: StorageAccessMode, enabled: Boolean): com.pocketsteward.app.metadata.MetadataEnrichment {
    return try {
        val fresh = freshEvidenceRecord(record, mode)
        if (enabled) container.metadataEnricher.enrich(fresh) else com.pocketsteward.app.metadata.MetadataEnrichment(fresh, false)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { com.pocketsteward.app.metadata.MetadataEnrichment(record, false,
        sourceError = "This source could not be inspected. Refresh its inventory or restore access before filing.") }
}

internal fun ScanViewModel.enableContentAndReplanFiling() {
    val preview = _preview.value ?: return
    if (preview.filingPresentation == null) return
    if (busy.value != null) return
    viewModelScope.launch {
        settingsRepository.setContentInspectionEnabled(true)
        continueFilingImageEvidence()
    }
}


internal fun ScanViewModel.analyzeAllFilingEvidence(retryUnavailable: Boolean = false) {
    if (hasActiveFilingWork || busy.value != null) return
    val current = _preview.value ?: return
    val session = filingSession ?: return
    if (current.filingPresentation?.reviewSessionId != session.reviewId) return
    viewModelScope.launch {
        try {
            val access = settingsRepository.storageAccessState.first()
            val mode = requireNotNull(access.mode) { "Restore storage access before analysis." }
            require(ReviewDraftPolicy.hasCurrentAccess(current, mode, access.safTreeUri)) { "Storage access changed. Rebuild the review first." }
            val privacy = workflowPrivacy()
            require(privacy.imageAnalysisEnabled || privacy.contentInspectionEnabled) { "Enable local image analysis or content inspection in Settings first." }
            val planned = withContext(Dispatchers.IO) {
                val artifacts = session.result.decisions.filterNot { decision ->
                    decision.evidence.any { it.kind == com.pocketsteward.app.filing.FilingEvidenceKind.SOURCE_UNAVAILABLE }
                }.map { it.artifact }
                com.pocketsteward.app.evidence.analysis.EvidenceAnalysisSourcePlanner.forReview(
                    artifacts, allRecordsForScopes(current.scopes), session.originalSources.orEmpty() + current.reviewedSources,
                    privacy.imageAnalysisEnabled, privacy.contentInspectionEnabled,
                ) { record -> sourceRootFor(record.stableRef, current.scopes) ?: current.scopes.first().root.rawValue() }
            }
            require(planned.sources.isNotEmpty()) { "This review has no eligible indexed image or document files for the enabled privacy settings. Refresh the inventory to include folder contents." }
            container.evidenceAnalysis.start(planned.sources, mode, access.safTreeUri.takeIf { mode == StorageAccessMode.SAF },
                privacy.imageAnalysisEnabled, privacy.contentInspectionEnabled, retryUnavailable, planned.folders)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { _error.value = failure.message ?: "Could not start evidence analysis." }
    }
}

internal fun ScanViewModel.pauseFilingEvidenceAnalysis() {
    viewModelScope.launch { try { container.evidenceAnalysis.pause() }
        catch (failure: Exception) { _error.value = failure.message ?: "Could not pause evidence analysis." } }
}
internal fun ScanViewModel.resumeFilingEvidenceAnalysis() {
    viewModelScope.launch { try { container.evidenceAnalysis.resume() }
        catch (failure: Exception) { _error.value = failure.message ?: "Could not resume evidence analysis." } }
}
