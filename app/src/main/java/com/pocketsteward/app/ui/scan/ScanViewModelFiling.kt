package com.pocketsteward.app.ui.scan

import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.content.index.ContentSearchDatabase
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.executor.StorageDigest
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.filing.FilingConfidence
import com.pocketsteward.app.filing.FilingEvidence
import com.pocketsteward.app.filing.FilingEvidenceKind
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GENERIC_TOP_LEVEL_FOLDERS = setOf(
    "android", "download", "downloads", "documents", "pictures", "movies", "music",
    "dcim", "alarms", "notifications", "ringtones", "podcasts", "audiobooks",
    "pocketsteward", "trash",
)

/**
 * Files loose incoming artifacts into durable project homes. This is separate
 * from Smart cleanup: the source root is an inbox and the destination may be
 * elsewhere in authorized shared storage.
 */
internal fun ScanViewModel.proposeInboxFiling(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working(
            label = "Planning inbox filing",
            detail = "Matching incoming artifacts to projects and releases",
        )

        try {
            val mode = settingsRepository.storageAccessState.first().mode
                ?: error("No storage access mode is active.")
            if (mode == StorageAccessMode.SAF) {
                proposeInboxFilingSaf(summary)
                return@launch
            }

            val sourceRootKeys = summary.scopes.map { it.root.rawValue().trimEnd('/') }.toSet()
            val records = allRecordsForScopes(summary.scopes)
                .filter { record ->
                    !record.isDirectory && record.parentRef?.trimEnd('/') in sourceRootKeys
                }

            if (records.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    "No loose files are waiting in the selected inbox. Files already inside folders were left alone.",
                )
                return@launch
            }

            val enriched = withContext(Dispatchers.IO) {
                records.map { record ->
                    val metadata = container.metadataEnricher.enrich(record)
                    if (metadata.changed) container.database.fileRecordDao().upsert(metadata.record)
                    record.stableRef to metadata
                }.toMap()
            }

            val contentDao = ContentSearchDatabase
                .getInstance(container.appContextForUi)
                .contentIndexDao()
            val indexedText = withContext(Dispatchers.IO) {
                records.associate { record ->
                    val segmentText = if (ContentExtractor.supports(record.extension)) {
                        runCatching {
                            contentDao.getSegmentsForDocument(record.stableRef)
                                .take(4)
                                .joinToString(" ") { it.body.take(2_000) }
                        }.getOrDefault("")
                    } else {
                        ""
                    }
                    record.stableRef to listOfNotNull(record.textPreview, segmentText.takeIf { it.isNotBlank() })
                        .joinToString(" ")
                }
            }

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
                    indexedText = indexedText[updated.stableRef].orEmpty(),
                )
            }

            val inferred = withContext(Dispatchers.Default) {
                InboxFilingEngine.resolve(
                    artifacts = artifacts,
                    persistedHomes = (savedHomes + favorites).distinctBy { it.path.lowercase() },
                    discoveredHomes = discovered,
                    projectKeywords = settingsRepository.projectKeywords.first(),
                    correctionRules = settingsRepository.correctionRules.first(),
                    storageRoot = storageRoot.absolutePath,
                )
            }
            val result = resolveDirectDestinationCollisions(inferred, gateway, storageRoot.absolutePath)

            val existingDirectories = linkedSetOf<String>()
            existingDirectories += topLevelDirectories
            existingDirectories += savedHomes.map { it.path }
            existingDirectories += favorites.map { it.path }
            // Known release children matter for friendly "existing" UI;
            // plan validation independently snapshots them live.
            for (home in (savedHomes + favorites + discovered).distinctBy { it.path.lowercase() }.take(100)) {
                val children = withContext(Dispatchers.IO) {
                    runCatching { gateway.listChildren(FileRef.Direct(home.path)) }.getOrDefault(emptyList())
                }
                existingDirectories += children.filter { it.isDirectory }.map { it.ref.rawValue().trimEnd('/') }
            }

            val plan = withContext(Dispatchers.Default) {
                InboxFilingPlanAdapter.build(
                    result = result,
                    storageRoot = storageRoot,
                    existingDirectories = existingDirectories,
                )
            }

            if (plan.operations.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    "Nothing in this inbox has enough project evidence to file safely. " +
                        "${result.unresolved.size} file(s) were left exactly where they are.",
                )
                return@launch
            }

            val notes = buildList {
                add("The selected landing folder is being treated as an inbox, not a permanent category tree.")
                add("Project ownership outranks file type. APK, ZIP, notes, and supporting assets can travel together when their evidence agrees.")
                add("Strong matches are selected by default. Probable matches are proposed but left unchecked. Unresolved files stay in the inbox.")
                add("Existing project homes are preferred over creating near-duplicate folders.")
                if (result.unresolved.isNotEmpty()) {
                    add("${result.unresolved.size} unresolved file(s) will remain in the inbox unless you handle them separately.")
                }
            }

            showPlanPreview(
                goal = "Inbox filing",
                operations = plan.operations,
                scopes = summary.scopes,
                scopeNotes = notes,
                authorizedDestinationRoots = plan.authorizedDestinationRoots,
                defaultSelectedSourceRefs = plan.defaultSelectedSourceRefs,
                filingPresentation = plan.presentation,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}


private suspend fun ScanViewModel.proposeInboxFilingSaf(summary: ScanUiState.Summary) {
    val scope = summary.scopes.singleOrNull() ?: run {
        _uiState.value = ScanUiState.Error(
            "Selected-folder filing currently needs one granted tree at a time.",
        )
        return
    }
    val rootKey = scope.root.rawValue().trimEnd('/')
    val records = allRecordsForScopes(summary.scopes)
        .filter { !it.isDirectory && it.parentRef?.trimEnd('/') == rootKey }
    if (records.isEmpty()) {
        _uiState.value = ScanUiState.Error(
            "No loose files are waiting at the top of the granted inbox. Files already inside folders were left alone.",
        )
        return
    }

    val enriched = withContext(Dispatchers.IO) {
        records.map { record ->
            val metadata = container.metadataEnricher.enrich(record)
            if (metadata.changed) container.database.fileRecordDao().upsert(metadata.record)
            record.stableRef to metadata
        }.toMap()
    }
    val contentDao = ContentSearchDatabase.getInstance(container.appContextForUi).contentIndexDao()
    val indexedText = withContext(Dispatchers.IO) {
        records.associate { record ->
            val segmentText = if (ContentExtractor.supports(record.extension)) {
                runCatching {
                    contentDao.getSegmentsForDocument(record.stableRef)
                        .take(4)
                        .joinToString(" ") { it.body.take(2_000) }
                }.getOrDefault("")
            } else ""
            record.stableRef to listOfNotNull(record.textPreview, segmentText.takeIf(String::isNotBlank)).joinToString(" ")
        }
    }

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
        )
    }
    if (plan.operations.isEmpty()) {
        _uiState.value = ScanUiState.Error(
            "Nothing in this granted inbox has enough project evidence to file safely. ${result.unresolved.size} file(s) stay where they are.",
        )
        return
    }
    showPlanPreview(
        goal = "Inbox filing",
        operations = plan.operations,
        scopes = summary.scopes,
        scopeNotes = listOf(
            "Selected-folder access can file only inside this granted tree; it cannot discover project homes elsewhere on the device.",
            "Strong matches are selected by default. Probable matches wait for review. Unresolved files stay in the inbox.",
        ),
        defaultSelectedSourceRefs = plan.defaultSelectedSourceRefs,
        filingPresentation = plan.presentation,
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