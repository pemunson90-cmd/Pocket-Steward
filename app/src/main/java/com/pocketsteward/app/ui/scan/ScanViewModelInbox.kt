package com.pocketsteward.app.ui.scan

import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.content.index.ContentSearchDatabase
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.inbox.ArtifactSignals
import com.pocketsteward.app.inbox.InboxFilingEngine
import com.pocketsteward.app.inbox.InboxPlanAdapter
import com.pocketsteward.app.saved.ProjectHierarchy
import com.pocketsteward.app.saved.ProjectHome
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Project-aware inbox filing. This is intentionally a planner only: it may
 * inspect metadata and propose cross-root destinations, but every mutation is
 * still ordinary CreateDirectory/Move operations reviewed by the same gate as
 * Smart cleanup.
 */
internal fun ScanViewModel.proposeInboxFiling(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        if (summary.mode != StorageAccessMode.DIRECT) {
            _uiState.value = ScanUiState.Error(
                "Project filing across storage needs full file-manager access. " +
                    "Selected-folder mode can still use Smart cleanup inside its granted tree.",
            )
            return@launch
        }

        _uiState.value = ScanUiState.Working(
            "Planning inbox filing",
            "Matching incoming files to durable project homes",
        )

        try {
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            val gateway = container.gatewayFor(StorageAccessMode.DIRECT)
            val fileDao = container.database.fileRecordDao()
            val configuredInboxes = settingsRepository.inboxRoots.first()
            val configuredInboxPaths = configuredInboxes.map { it.path.trimEnd('/') }.toSet()

            val recordsByScope = summary.scopes.associateWith { scope ->
                fileDao.getFilesUnderScopeRoot(scope.root.rawValue())
                    .filter { record ->
                        // Inbox filing is intentionally conservative about an
                        // existing folder structure: direct loose arrivals are
                        // candidates; nested files stay where they are unless a
                        // later explicit workflow asks for them.
                        record.parentRef?.trimEnd('/') == scope.root.rawValue().trimEnd('/')
                    }
            }
            val sourceRecords = recordsByScope.values.flatten().distinctBy { it.stableRef }
            if (sourceRecords.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    "No loose files are sitting directly in ${summary.scopeLabel}; nothing to file.",
                )
                return@launch
            }

            val topLevelEntries = withContext(Dispatchers.IO) {
                gateway.listChildren(FileRef.Direct(storageRoot))
            }
            val topLevelFolders = topLevelEntries.filter { entry ->
                entry.isDirectory && entry.displayName.lowercase() !in GENERIC_STORAGE_FOLDERS
            }

            val persistedHomes = settingsRepository.projectHomes.first()
            val favoriteHomes = settingsRepository.favoriteDestinations.first().map { favorite ->
                ProjectHome(
                    name = favorite.name,
                    path = favorite.path,
                    aliases = listOf(favorite.name),
                    hierarchy = ProjectHierarchy.VERSIONED,
                )
            }
            val discoveredHomes = topLevelFolders
                .filterNot { it.ref.rawValue().trimEnd('/') in configuredInboxPaths }
                .map { folder ->
                    ProjectHome(
                        name = folder.displayName,
                        path = folder.ref.rawValue(),
                        aliases = listOf(folder.displayName),
                        hierarchy = ProjectHierarchy.VERSIONED,
                    )
                }
            val candidateHomes = (persistedHomes + favoriteHomes + discoveredHomes)
                .distinctBy { it.path.trimEnd('/').lowercase() }

            val privacy = settingsRepository.privacySettings.first()
            val contentDao = if (privacy.contentInspectionEnabled) {
                ContentSearchDatabase.getInstance(container.appContextForUi).contentIndexDao()
            } else {
                null
            }

            val signals = withContext(Dispatchers.IO) {
                sourceRecords.map { record ->
                    val enrichment = container.metadataEnricher.enrich(record)
                    if (enrichment.changed) {
                        fileDao.update(enrichment.record)
                    }
                    val enriched = enrichment.record
                    val indexedText = when {
                        !enriched.textPreview.isNullOrBlank() -> enriched.textPreview.orEmpty()
                        contentDao != null && enriched.extension.lowercase() in CONTENT_EVIDENCE_EXTENSIONS ->
                            contentDao.getSegmentsForDocument(enriched.stableRef)
                                .take(3)
                                .joinToString("\n") { it.body.take(2_000) }
                        else -> ""
                    }
                    ArtifactSignals(
                        stableRef = enriched.stableRef,
                        displayName = enriched.displayName,
                        extension = enriched.extension,
                        sizeBytes = enriched.sizeBytes,
                        modifiedAt = enriched.modifiedAt,
                        apkLabel = enrichment.apkLabel,
                        apkPackageName = enriched.apkPackageName,
                        apkVersionName = enriched.apkVersionName,
                        apkVersionCode = enrichment.apkVersionCode,
                        apkSignerSha256 = enrichment.apkSignerSha256,
                        archiveSample = enrichment.archiveSample,
                        indexedText = indexedText,
                    )
                }
            }

            val analysis = withContext(Dispatchers.Default) {
                InboxFilingEngine.analyze(
                    artifacts = signals,
                    projectHomes = candidateHomes,
                    projectKeywords = settingsRepository.projectKeywords.first(),
                    correctionRules = settingsRepository.correctionRules.first(),
                    storageRootPath = storageRoot,
                )
            }

            if (analysis.decisions.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    "Pocket Steward couldn't identify a project home strongly enough for any loose file. " +
                        "Nothing was moved; the files stay in the inbox.",
                )
                return@launch
            }

            val existingDirectories = linkedSetOf(storageRoot)
            topLevelEntries.filter { it.isDirectory }.forEach { existingDirectories += it.ref.rawValue().trimEnd('/') }
            val protectedDirectories = linkedSetOf<String>()

            // Only inspect folders that actually won a filing decision. This
            // keeps discovery shallow even on a storage root containing many
            // large project trees.
            val selectedHomePaths = analysis.decisions
                .map { it.projectHome.path.trimEnd('/') }
                .distinct()
            withContext(Dispatchers.IO) {
                for (homePath in selectedHomePaths) {
                    val home = FileRef.Direct(homePath)
                    val parentPath = homePath.substringBeforeLast('/', missingDelimiterValue = "")
                    if (parentPath.isNotBlank()) {
                        val parent = FileRef.Direct(parentPath)
                        if (gateway.exists(parent) && runCatching { gateway.stat(parent).isDirectory }.getOrDefault(false)) {
                            existingDirectories += parentPath
                        }
                    }
                    if (!gateway.exists(home) || !runCatching { gateway.stat(home).isDirectory }.getOrDefault(false)) {
                        continue
                    }
                    existingDirectories += homePath
                    if (gateway.exists(home.child(DO_NOT_SORT_MARKER))) {
                        protectedDirectories += homePath
                    }
                    gateway.listChildren(home).filter { it.isDirectory }.forEach { child ->
                        val path = child.ref.rawValue().trimEnd('/')
                        existingDirectories += path
                        if (gateway.exists(child.ref.child(DO_NOT_SORT_MARKER))) {
                            protectedDirectories += path
                        }
                    }
                }
            }

            val planned = withContext(Dispatchers.Default) {
                InboxPlanAdapter.build(
                    analysis = analysis,
                    storageRootPath = storageRoot,
                    existingDirectories = existingDirectories,
                    protectedDirectories = protectedDirectories,
                )
            }
            if (planned.operations.isEmpty()) {
                _uiState.value = ScanUiState.Error(
                    planned.notes.firstOrNull()
                        ?: "No inbox filing action remained after destination safety checks.",
                )
                return@launch
            }

            val scopeRoots = summary.scopes.map { it.root.rawValue().trimEnd('/') }
            val oneTimeInbox = scopeRoots.none { it in configuredInboxPaths }
            val notes = buildList {
                if (oneTimeInbox) {
                    add("This scan is being treated as a one-time inbox. Configure inbox roots in Settings if you want it remembered.")
                }
                addAll(planned.notes)
            }

            showPlanPreview(
                goal = "Inbox filing",
                operations = planned.operations,
                scopes = summary.scopes,
                scopeNotes = notes,
                authorizedDestinationRoots = planned.authorizedDestinationRoots,
                filingHints = planned.filingHints,
                preferredSelectedSourceRefs = planned.strongSourceRefs,
                pendingProjectHomes = planned.projectHomesToRemember,
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

private val CONTENT_EVIDENCE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "pdf", "doc", "docx", "odt", "rtf", "html", "htm",
)

private val GENERIC_STORAGE_FOLDERS = setOf(
    "android", "download", "downloads", "dcim", "documents", "movies", "music", "pictures",
    "podcasts", "notifications", "ringtones", "alarms", "audiobooks",
)
