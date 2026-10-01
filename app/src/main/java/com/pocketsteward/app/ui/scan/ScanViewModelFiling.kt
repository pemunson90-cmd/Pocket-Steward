package com.pocketsteward.app.ui.scan

import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.content.index.ContentIndexCandidate
import com.pocketsteward.app.content.index.IndexedExtractionStatus
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.executor.StorageDigest
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
internal fun ScanViewModel.proposeInboxFiling(summary: ScanUiState.Summary, checkpointOnly: Boolean = false, scheduled: com.pocketsteward.app.scheduled.PendingCleanupSuggestion? = null) {
    filingPlanningJob?.cancel()
    filingPlanningJob = viewModelScope.launch {
        _uiState.value = ScanUiState.Working(
            label = "Planning inbox filing",
            detail = "Matching incoming artifacts to projects and releases",
        )

        try {
            val mode = settingsRepository.storageAccessState.first().mode
                ?: error("No storage access mode is active.")
            val allowlist = scheduled?.newFileRefs?.toHashSet()
            val extraNotes = scheduled?.let { listOf("Scheduled filing considers only ${it.newFileRefs.size} saved new-file references that are still direct inbox children. Older files and nested folder contents are left alone.") }.orEmpty()
            if (mode == StorageAccessMode.SAF) {
                proposeInboxFilingSaf(summary, checkpointOnly, allowlist, extraNotes)
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
            val records = intake.records.filter { allowlist == null || (!it.isDirectory && it.stableRef in allowlist) }
            val skippedFolders = 0

            if (records.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    if (checkpointOnly) "No files are waiting in an Uncertain checkpoint under the configured inboxes."
                    else "No loose files are waiting in the selected inbox. $skippedFolders existing folder(s) were left alone.",
                )
                return@launch
            }

            val metadataEnabled = settingsRepository.privacySettings.first().metadataIndexingEnabled
            val enriched = withContext(Dispatchers.IO) {
                records.map { record ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val metadata = enrichFreshFilingRecord(record, summary.mode, metadataEnabled)
                    if (metadata.changed) container.database.fileRecordDao().upsert(metadata.record)
                    record.stableRef to metadata
                }.toMap()
            }

            val freshRecords = records.map { enriched.getValue(it.stableRef).record }
            val imageEvidence = prepareFilingImages(freshRecords)
            val content = prepareFilingContent(freshRecords, summary)
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
                container.projectKnowledge.discover(storageRoot, (settingsRepository.inboxRoots.first().map { it.path } + sourceRootKeys).distinct())
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
                    apkPackageName = updated.apkPackageName,
                    apkVersionName = updated.apkVersionName,
                    apkLabel = metadata.apkLabel,
                    apkVersionCode = metadata.apkVersionCode,
                    archiveSample = metadata.archiveSample,
            captureDate = metadata.captureDate, mediaArtist = metadata.mediaArtist, mediaAlbum = metadata.mediaAlbum,
                    imageLabels = imageEvidence[updated.stableRef]?.let { insight -> insight.labels.map { it.label } + if (insight.likelyScreenshot) listOf("screenshot") else emptyList() }.orEmpty(),
                    imageText = imageEvidence[updated.stableRef]?.detectedText.orEmpty(),
                    indexedText = indexedText[updated.stableRef].orEmpty(),
                    isDirectory = updated.isDirectory,
                )
            }

            val inferred = withContext(Dispatchers.Default) {
                InboxFilingEngine.resolve(
                    artifacts = artifacts,
                    persistedHomes = (savedHomes + favorites).distinctBy { it.path.lowercase() },
                    discoveredHomes = observedHomes + discovered,
                    projectKeywords = settingsRepository.projectKeywords.first(),
                    correctionRules = settingsRepository.correctionRules.first(),
                    storageRoot = storageRoot.absolutePath,
                    newProjectRoles = settingsRepository.hierarchyTemplate.first().roleFolders,
                )
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
            // Known release children matter for friendly "existing" UI;
            // plan validation independently snapshots them live.
            for (home in (savedHomes + favorites + observedHomes + discovered).distinctBy { it.path.lowercase() }.take(100)) {
                val children = withContext(Dispatchers.IO) {
                    runCatching { gateway.listChildren(FileRef.Direct(home.path)) }.getOrDefault(emptyList())
                }
                existingDirectories += children.filter { it.isDirectory }.map { it.ref.rawValue().trimEnd('/') }
            }

            val conventional = com.pocketsteward.app.filing.FilingReleaseConvention.reconcile(inferred, existingDirectories)
            val result = resolveDirectDestinationCollisions(conventional, gateway, storageRoot.absolutePath)
            val plan = withContext(Dispatchers.Default) {
                InboxFilingPlanAdapter.build(
                    result = result,
                    storageRoot = storageRoot,
                    existingDirectories = existingDirectories,
                    retainedUncertainSourceRefs = intake.retainedUncertainSourceRefs,
                )
            }

            filingSession = FilingSession(result, storageRoot, existingDirectories, intake.retainedUncertainSourceRefs,
                (savedHomes + favorites + observedHomes + discovered).distinctBy { it.path.lowercase() })

            val notes = buildList {
                add("The selected landing folder is being treated as an inbox, not a permanent category tree.")
                add("Project ownership outranks file type. APK, ZIP, notes, and supporting assets can travel together when their evidence agrees.")
                add(if (checkpointOnly) "Strong matches are selected by default. Probable matches wait for review. Anything still unresolved stays in its existing Uncertain checkpoint."
                    else "Strong matches are selected by default. Probable matches are proposed but left unchecked. Unresolved files and intact folders move to Uncertain after review.")
                add(content.summary)
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
                defaultSelectedSourceRefs = plan.defaultSelectedSourceRefs,
                filingPresentation = plan.presentation.copy(skippedInboxFolders = skippedFolders, reviewingUncertain = checkpointOnly),
                previousReviewedSources = enriched.values.mapNotNull { item ->
                    com.pocketsteward.app.plan.SourcePreconditions.from(item.record)?.let { item.record.stableRef to it }
                }.toMap(),
            )
            if (scheduled != null && _uiState.value is ScanUiState.PlanPreview) settingsRepository.clearPendingCleanupSuggestion()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}


private suspend fun ScanViewModel.proposeInboxFilingSaf(summary: ScanUiState.Summary, checkpointOnly: Boolean, allowlist: Set<String>?, extraNotes: List<String>) {
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
    val records = intake.records
    if (records.isEmpty()) {
        _uiState.value = ScanUiState.Error(
            if (checkpointOnly) "No files are waiting in an Uncertain checkpoint inside the granted inbox."
            else "No loose files are waiting at the top of the granted inbox. $skippedFolders existing folder(s) were left alone.",
        )
        return
    }

    val metadataEnabled = settingsRepository.privacySettings.first().metadataIndexingEnabled
    val enriched = withContext(Dispatchers.IO) {
        records.map { record ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val metadata = enrichFreshFilingRecord(record, summary.mode, metadataEnabled)
            if (metadata.changed) container.database.fileRecordDao().upsert(metadata.record)
            record.stableRef to metadata
        }.toMap()
    }
    val freshRecords = records.map { enriched.getValue(it.stableRef).record }
    val imageEvidence = prepareFilingImages(freshRecords)
    val content = prepareFilingContent(freshRecords, summary)
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
            apkPackageName = updated.apkPackageName,
            apkVersionName = updated.apkVersionName,
            apkLabel = metadata.apkLabel,
            apkVersionCode = metadata.apkVersionCode,
            archiveSample = metadata.archiveSample,
            captureDate = metadata.captureDate, mediaArtist = metadata.mediaArtist, mediaAlbum = metadata.mediaAlbum,
            imageLabels = imageEvidence[updated.stableRef]?.let { insight -> insight.labels.map { it.label } + if (insight.likelyScreenshot) listOf("screenshot") else emptyList() }.orEmpty(),
                    imageText = imageEvidence[updated.stableRef]?.detectedText.orEmpty(),
            indexedText = indexedText[updated.stableRef].orEmpty(),
        )
    }
    val result = withContext(Dispatchers.Default) {
        InboxFilingEngine.resolve(
            artifacts = artifacts,
            persistedHomes = emptyList(),
            discoveredHomes = homes,
            projectKeywords = settingsRepository.projectKeywords.first(),
            correctionRules = settingsRepository.correctionRules.first(),
            storageRoot = scope.root.rawValue(),
            newProjectRoles = settingsRepository.hierarchyTemplate.first().roleFolders,
        )
    }
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
        )
    }
    filingSession = FilingSession(result, scope.root, emptySet(), intake.retainedUncertainSourceRefs, homes, existingHomeRefs)
    showPlanPreview(
        goal = if (checkpointOnly) "Sort Uncertain" else "Inbox filing",
        operations = plan.operations,
        scopes = summary.scopes,
        scopeNotes = listOf(
            content.summary,
            "Selected-folder access can file only inside this granted tree; it cannot discover project homes elsewhere on the device.",
            if (checkpointOnly) "Strong matches are selected by default. Probable matches wait for review. Unresolved files stay in their existing checkpoint."
            else "Strong matches are selected by default. Probable matches wait for review. Unresolved loose files move to Uncertain after review.",
            "$skippedFolders existing folder(s) remain in the inbox; this pass does not move folder contents without a reviewed snapshot.",
        ) + extraNotes + listOfNotNull(
            if (!metadataEnabled) "Metadata inspection is off; camera dates, media tags and archive entries were not read." else null,
            enriched.values.count { it.archiveComplete == false }.takeIf { it > 0 }?.let { "$it archives had partial or unavailable inspection. Only observed entry names were used as evidence." },
        ),
        defaultSelectedSourceRefs = plan.defaultSelectedSourceRefs,
        filingPresentation = plan.presentation.copy(skippedInboxFolders = skippedFolders, reviewingUncertain = checkpointOnly),
        previousReviewedSources = enriched.values.mapNotNull { item ->
            com.pocketsteward.app.plan.SourcePreconditions.from(item.record)?.let { item.record.stableRef to it }
        }.toMap(),
    )
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
    if (!settingsRepository.privacySettings.first().contentInspectionEnabled) return FilingContent(emptyMap(), 0, 0, 0, enabled = false)
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
        val reusable = !record.extension.equals("pdf", true) || com.pocketsteward.app.content.index.ContentIndexPolicy.canReuse(cachedPdfs[record.stableRef], record, budget.profile)
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

private suspend fun ScanViewModel.prepareFilingImages(records: List<FileRecord>): Map<String, com.pocketsteward.app.image.ImageInsight> {
    val privacy = settingsRepository.privacySettings.first()
    if (!privacy.imageAnalysisEnabled) return emptyMap()
    val candidates = records.filter { !it.isDirectory && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic") }
    val result = linkedMapOf<String, com.pocketsteward.app.image.ImageInsight>()
    for ((index, record) in candidates.withIndex()) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        _uiState.value = ScanUiState.Working("Reading local image evidence", "${index + 1} of ${candidates.size} · ${record.displayName}", processed = index, total = candidates.size)
        val insight = withContext(Dispatchers.IO) { container.imageUnderstanding.analyze(record, inspectText = privacy.contentInspectionEnabled) }
        if (insight != null) result[record.stableRef] = insight
    }
    return result
}

private suspend fun ScanViewModel.enrichFreshFilingRecord(record: FileRecord, mode: StorageAccessMode, enabled: Boolean): com.pocketsteward.app.metadata.MetadataEnrichment {
    val fresh = freshEvidenceRecord(record, mode)
    return if (enabled) container.metadataEnricher.enrich(fresh) else com.pocketsteward.app.metadata.MetadataEnrichment(fresh, false)
}

internal fun ScanViewModel.enableContentAndReplanFiling() {
    val summary = _summary.value ?: return
    val preview = _preview.value ?: return
    val checkpointOnly = preview.filingPresentation?.reviewingUncertain ?: return
    if (busy.value != null) return
    viewModelScope.launch {
        settingsRepository.setContentInspectionEnabled(true)
        proposeInboxFiling(summary, checkpointOnly)
    }
}
