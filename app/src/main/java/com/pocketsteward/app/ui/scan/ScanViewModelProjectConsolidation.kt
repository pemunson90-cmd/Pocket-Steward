package com.pocketsteward.app.ui.scan

import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.projects.ProjectConsolidationPlanner
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ScanViewModel.proposeProjectConsolidation(sourcePath: String, destinationPath: String) {
    if (busy.value != null) return
    filingPlanningJob?.cancel()
    filingPlanningJob = viewModelScope.launch {
        try {
            val access = settingsRepository.storageAccessState.first()
            val mode = requireNotNull(access.mode) { "Restore storage access before consolidating homes." }
            val homes = settingsRepository.projectHomes.first()
            require(homes.any { it.path == sourcePath } && homes.any { it.path == destinationPath }) { "Choose two known project homes." }
            val requestedSource = parseFileRef(sourcePath)
            val requestedDestination = parseFileRef(destinationPath)
            val (source, destination) = when (mode) {
                StorageAccessMode.DIRECT -> {
                    val source = requestedSource
                    val destination = requestedDestination
                    require(source is FileRef.Direct && destination is FileRef.Direct) { "Choose homes available through direct file access." }
                    require(source != destination && !source.absolutePath.startsWith("${destination.absolutePath.trimEnd('/')}/") &&
                        !destination.absolutePath.startsWith("${source.absolutePath.trimEnd('/')}/")) { "Choose separate, non-nested project homes." }
                    source to destination
                }
                StorageAccessMode.SAF -> withContext(Dispatchers.IO) {
                    require(requestedSource is FileRef.Saf && requestedDestination is FileRef.Saf) { "Choose homes inside the currently granted tree." }
                    val grant = requireNotNull(access.safTreeUri) { "Restore the selected-folder grant." }
                    val context = container.appContextForUi
                    require(com.pocketsteward.app.storage.SafScopeAccess.contains(context, grant, sourcePath) &&
                        com.pocketsteward.app.storage.SafScopeAccess.contains(context, grant, destinationPath)) { "Both homes must be inside the currently accessible grant." }
                    val source = com.pocketsteward.app.storage.SafScopeAccess.bind(context, grant, sourcePath)
                    val destination = com.pocketsteward.app.storage.SafScopeAccess.bind(context, grant, destinationPath)
                    require(!com.pocketsteward.app.storage.SafScopeAccess.contains(context, source.rawValue(), destination.rawValue()) &&
                        !com.pocketsteward.app.storage.SafScopeAccess.contains(context, destination.rawValue(), source.rawValue())) { "Choose separate, non-nested project homes." }
                    source to destination
                }
            }
            val scopes = listOf(ScanScope("Source project", source), ScanScope("Chosen project home", destination))
            for (scope in scopes) {
                _uiState.value = ScanUiState.Working("Preparing project consolidation", "Fresh inventory · ${scope.label}")
                withContext(Dispatchers.IO) {
                    container.scanLocks.withScanLock(scope.root.rawValue()) {
                        container.fileScanner(mode).scan(scope.root) { progress ->
                            _uiState.value = ScanUiState.Working("Preparing project consolidation", progress.currentDirectoryName ?: scope.label, processed = progress.processedCount)
                        }
                    }
                }
            }
            val plan = withContext(Dispatchers.IO) {
                val cancellation = currentCoroutineContext()
                val proposed = ProjectConsolidationPlanner.build(source, destination,
                    container.database.fileRecordDao().getAllUnderScopeRoot(source.rawValue()),
                    container.database.fileRecordDao().getAllUnderScopeRoot(destination.rawValue()),
                    checkCancelled = { cancellation.ensureActive() })
                if (mode == StorageAccessMode.DIRECT) proposed else {
                    val gateway = container.gatewayFor(mode)
                    val refused = linkedMapOf<String, String>()
                    for (move in proposed.moves) {
                        cancellation.ensureActive()
                        require(com.pocketsteward.app.storage.SafScopeAccess.contains(container.appContextForUi, source.rawValue(), move.source.rawValue())) {
                            "A source moved outside its reviewed home. Refresh both homes."
                        }
                        if (gateway.stat(move.source).isDirectory) gateway.intactFolderMoveRefusal(move.source)?.let { refused[move.source.rawValue()] = it }
                    }
                    proposed.copy(moves = proposed.moves.filterNot { it.source.rawValue() in refused },
                        held = proposed.held + container.database.fileRecordDao().getByStableRefs(refused.keys.toList()),
                        heldReasons = proposed.heldReasons + refused)
                }
            }
            require(plan.moves.isNotEmpty()) { "Nothing can be moved without a naming conflict or protection refusal. Both homes remain unchanged." }
            filingSession = null
            showPlanPreview("Consolidate project homes", plan.moves, scopes,
                scopeNotes = listOf("You chose $destinationPath as the merged home. Existing matching folders are merged by structure; other folders move intact.",
                    "${plan.held.size} conflicting or protected source entries stay in place. No files are overwritten or deleted.",
                    "Held examples: ${plan.held.take(10).joinToString { it.displayName + ": " + plan.heldReasons[it.stableRef].orEmpty() }.ifEmpty { "none" }}",
                    "The original home and any empty folders remain. Review Tasks and remaining source files before removing an obsolete home from Settings."),
                authorizedDestinationRoots = listOfNotNull(destination as? FileRef.Direct), defaultSelectedSourceRefs = emptySet())
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { _uiState.value = ScanUiState.Error(failure.message ?: "Could not prepare project consolidation.") }
    }
}
