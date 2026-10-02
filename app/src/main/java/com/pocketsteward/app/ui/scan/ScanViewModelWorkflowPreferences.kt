package com.pocketsteward.app.ui.scan

import android.net.Uri
import android.provider.DocumentsContract
import com.pocketsteward.app.saved.WorkflowDestination
import com.pocketsteward.app.saved.WorkflowPreferences
import com.pocketsteward.app.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal suspend fun ScanViewModel.workflowPrivacy() = settingsRepository.privacySettings.first().let {
    activeWorkflowPreferences?.permittedBy(it) ?: it
}

/** Preferences name a location; live permissions, existence and the ordinary plan validator authorize it. */
internal suspend fun ScanViewModel.workflowDestination(scope: ScanScope, mode: StorageAccessMode,
    preference: WorkflowPreferences? = activeWorkflowPreferences): FileRef? = withContext(Dispatchers.IO) {
    preference ?: return@withContext null
    when (preference.destination) {
        WorkflowDestination.DEFAULT -> null
        WorkflowDestination.INBOX_LOCAL -> scope.root
        WorkflowDestination.DOCUMENTS -> {
            val parent = if (mode == StorageAccessMode.SAF) scope.root else container.gatewayFor(mode).rootOf(StorageScope.Broad)
            val matches = container.gatewayFor(mode).listChildren(parent).filter { it.displayName.equals("Documents", true) }
            require(matches.size <= 1 && matches.all { it.isDirectory }) { "Documents has a naming conflict. Choose an existing destination explicitly." }
            matches.singleOrNull()?.ref ?: parent.child("Documents")
        }
        WorkflowDestination.CHOSEN_FOLDER -> {
            val configured = requireNotNull(preference.destinationFolder)
            val ref = if (mode == StorageAccessMode.DIRECT) {
                require(configured.startsWith('/')) { "This workflow destination needs selected-folder access." }
                val root = container.gatewayFor(mode).rootOf(StorageScope.Broad) as FileRef.Direct
                require(DirectProtection.refusalDestination(root.absolutePath, "${configured.trimEnd('/')}/.pocket-steward-proposal") == null) { "Workflow destination is protected or outside shared storage." }
                FileRef.Direct(configured)
            } else {
                require(configured.startsWith("content://")) { "This workflow destination needs full file access." }
                val tree = Uri.parse(requireNotNull(settingsRepository.storageAccessState.first().safTreeUri))
                val uri = Uri.parse(configured)
                require(uri.authority == tree.authority) { "Workflow destination belongs to another provider." }
                val id = if (DocumentsContract.isDocumentUri(container.appContextForUi, uri)) DocumentsContract.getDocumentId(uri)
                    else DocumentsContract.getTreeDocumentId(uri)
                val bound = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                require(SafScopeAccess.contains(container.appContextForUi, scope.root.rawValue(), bound)) { "Choose a destination inside this scanned tree." }
                FileRef.Saf(bound)
            }
            require(container.gatewayFor(mode).exists(ref) && container.gatewayFor(mode).stat(ref).isDirectory) { "Workflow destination is unavailable. Choose a current folder." }
            if (ref is FileRef.Saf) require(SafProtection(container.appContextForUi).refusalDestination(Uri.parse(ref.documentUri), "workflow") == null) { "Workflow destination protection could not be verified." }
            ref
        }
    }
}

internal data class WorkflowDestinationSetup(val root: FileRef, val prelude: List<com.pocketsteward.app.plan.PlannedOperation>, val authorization: List<FileRef.Direct>)
internal suspend fun ScanViewModel.workflowDestinationSetup(root: FileRef, mode: StorageAccessMode): WorkflowDestinationSetup = withContext(Dispatchers.IO) {
    if (container.gatewayFor(mode).exists(root)) {
        require(container.gatewayFor(mode).stat(root).isDirectory) { "Workflow destination is no longer a folder." }
        WorkflowDestinationSetup(root, emptyList(), listOfNotNull(root as? FileRef.Direct))
    } else {
        val parent: FileRef
        val name: String
        when (root) {
            is FileRef.Child -> { parent = root.parent; name = root.name }
            is FileRef.Direct -> {
                val file = java.io.File(root.absolutePath)
                parent = FileRef.Direct(requireNotNull(file.parent)); name = file.name
            }
            is FileRef.Saf -> error("Workflow destination is unavailable. Choose a current folder.")
        }
        require(container.gatewayFor(mode).exists(parent) && container.gatewayFor(mode).stat(parent).isDirectory) { "Workflow destination parent is unavailable." }
        WorkflowDestinationSetup(root, listOf(com.pocketsteward.app.plan.PlannedOperation.CreateDirectory(parent, name,
            "Create the workflow destination only after review.")), listOfNotNull(parent as? FileRef.Direct))
    }
}
