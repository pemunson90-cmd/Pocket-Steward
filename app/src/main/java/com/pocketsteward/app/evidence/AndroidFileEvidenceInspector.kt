package com.pocketsteward.app.evidence

import com.pocketsteward.app.content.index.ContentSearchDatabase
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.flow.first

/** Android wiring; the shared inspector itself remains independently testable. */
internal fun createFileEvidenceInspector(container: AppContainer): FileEvidenceInspector = with(container) {
    val appContext = appContextForUi
        com.pocketsteward.app.evidence.FileEvidenceInspector(
            resolve = { ref -> database.fileRecordDao().getByStableRef(ref) ?: ContentSearchDatabase.getInstance(appContext).contentIndexDao().getDocument(ref)?.let { doc ->
                com.pocketsteward.app.data.db.FileRecord(stableRef = ref, displayName = doc.displayName, extension = doc.extension, mimeType = null,
                    absolutePathOrUri = ref, parentRef = doc.parentRef, sizeBytes = doc.sizeBytes, createdAt = null, modifiedAt = doc.modifiedAt,
                    lastScannedAt = doc.indexedAt, isDirectory = false, isHidden = false)
            } },
            currentMode = { settingsRepository.storageAccessState.first().mode },
            permitted = { request, mode ->
                val access = settingsRepository.storageAccessState.first()
                if (access.mode != mode) false else when (mode) {
                    StorageAccessMode.DIRECT -> {
                        val root = (library.root(access) as? FileRef.Direct)?.absolutePath?.trimEnd('/')
                        root != null && request.ref.startsWith("$root/")
                    }
                    StorageAccessMode.SAF -> access.safTreeUri?.let {
                        com.pocketsteward.app.storage.SafScopeAccess.contains(appContext, it, request.ref)
                    } ?: false
                }
            },
            refusal = { request, mode -> when (mode) {
                StorageAccessMode.DIRECT -> {
                    @Suppress("DEPRECATION") val root = android.os.Environment.getExternalStorageDirectory().absolutePath
                    com.pocketsteward.app.storage.DirectProtection.refusal(root, request.ref)
                }
                StorageAccessMode.SAF -> com.pocketsteward.app.storage.SafProtection(appContext).refusal(android.net.Uri.parse(request.ref))
            } },
            observe = { ref, mode -> gatewayFor(mode).stat(com.pocketsteward.app.storage.parseFileRef(ref)) },
            fingerprint = { record, mode -> contentInspector(mode).evidenceFingerprint(record) },
            privacy = { settingsRepository.privacySettings.first().let { com.pocketsteward.app.evidence.EvidencePrivacy(it.metadataIndexingEnabled, it.contentInspectionEnabled, it.imageAnalysisEnabled) } },
            metadata = { record -> metadataEnricher.enrich(record) },
            document = { record, mode, requestedRoot ->
                val access = settingsRepository.storageAccessState.first()
                val root = requestedRoot ?: requireNotNull(library.root(access)).rawValue()
                val repository = contentIndexRepository(mode)
                val inspected = repository.ensureDocument(com.pocketsteward.app.content.index.ContentIndexCandidate(record, root), com.pocketsteward.app.content.ContentInspectionBudget.FILING)
                com.pocketsteward.app.evidence.DocumentEvidenceMaterial(inspected.document, repository.segments(record.stableRef))
            },
            image = { record, text ->
                val observed = com.pocketsteward.app.image.ImageReviewBatch(imageUnderstanding, com.pocketsteward.app.image.ImageReviewBudget(1, if (text) 1 else 0)).inspect(listOf(record), text)
                com.pocketsteward.app.evidence.ImageEvidenceMaterial(observed.evidence[record.stableRef], observed.coverage.summary)
            },
            rules = { settingsRepository.correctionRules.first() },
        )
}
