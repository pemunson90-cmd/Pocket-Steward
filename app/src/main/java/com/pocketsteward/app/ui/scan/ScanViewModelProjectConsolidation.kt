package com.pocketsteward.app.ui.scan

import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.projects.ProjectConsolidationPlanner
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
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
            require(settingsRepository.storageAccessState.first().mode == StorageAccessMode.DIRECT) { "Project consolidation currently requires direct storage access." }
            val homes = settingsRepository.projectHomes.first()
            require(homes.any { it.path == sourcePath } && homes.any { it.path == destinationPath }) { "Choose two known project homes." }
            val source = FileRef.Direct(sourcePath.trimEnd('/'))
            val destination = FileRef.Direct(destinationPath.trimEnd('/'))
            require(source != destination && !source.absolutePath.startsWith("${destination.absolutePath}/") && !destination.absolutePath.startsWith("${source.absolutePath}/")) { "Choose separate, non-nested project homes." }
            val scopes = listOf(ScanScope("Source project", source), ScanScope("Chosen project home", destination))
            for (scope in scopes) {
                _uiState.value = ScanUiState.Working("Preparing project consolidation", "Fresh inventory · ${scope.label}")
                container.scanLocks.withScanLock((scope.root as FileRef.Direct).absolutePath) {
                    container.fileScanner(StorageAccessMode.DIRECT).scan(scope.root) { progress ->
                        _uiState.value = ScanUiState.Working("Preparing project consolidation", progress.currentDirectoryName ?: scope.label, processed = progress.processedCount.toInt())
                    }
                }
            }
            val plan = withContext(Dispatchers.IO) {
                ProjectConsolidationPlanner.build(source, destination,
                    container.database.fileRecordDao().getAllUnderScopeRoot(source.absolutePath),
                    container.database.fileRecordDao().getAllUnderScopeRoot(destination.absolutePath))
            }
            require(plan.moves.isNotEmpty()) { "Nothing can be moved without a naming conflict or protection refusal. Both homes remain unchanged." }
            filingSession = null
            showPlanPreview("Consolidate project homes", plan.moves, scopes,
                scopeNotes = listOf("You chose $destinationPath as the merged home. Existing matching folders are merged by structure; other folders move intact.",
                    "${plan.held.size} conflicting or protected source entries stay in place. No files are overwritten or deleted.",
                    "Held examples: ${plan.held.take(10).joinToString { it.displayName }.ifEmpty { "none" }}",
                    "The original home and any empty folders remain. Review Tasks and remaining source files before removing an obsolete home from Settings."),
                authorizedDestinationRoots = listOf(destination), defaultSelectedSourceRefs = emptySet())
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { _uiState.value = ScanUiState.Error(failure.message ?: "Could not prepare project consolidation.") }
    }
}
