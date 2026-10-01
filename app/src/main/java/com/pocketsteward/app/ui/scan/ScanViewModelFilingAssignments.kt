package com.pocketsteward.app.ui.scan

import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.filing.FilingAssignments
import com.pocketsteward.app.filing.FilingRole
import com.pocketsteward.app.filing.InboxFilingEngine
import com.pocketsteward.app.filing.InboxFilingPlanAdapter
import com.pocketsteward.app.filing.InboxFilingResult
import com.pocketsteward.app.filing.InboxFilingSafPlanAdapter
import com.pocketsteward.app.filing.ProjectHomeCandidate
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class FilingSession(
    val result: InboxFilingResult,
    val root: FileRef,
    val existingDirectories: Set<String>,
    val retainedUncertainSourceRefs: Set<String>,
    val homes: List<ProjectHomeCandidate>,
    val existingSafHomes: Map<String, FileRef> = emptyMap(),
)

internal fun ScanViewModel.assignFilingFiles(sourceRefs: Set<String>, projectTitle: String, role: FilingRole, chosenHomePath: String? = null, releaseFolder: String? = null) {
    if (filingEditJob?.isActive == true || busy.value != null) return
    val session = filingSession ?: return
    val current = _preview.value ?: return
    if (current.filingPresentation == null) return
    filingEditJob = viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Updating project assignment", "Rebuilding and validating the whole preview")
        try {
            val title = projectTitle.trim()
            require(InboxFilingEngine.sanitizeSegment(title) == title) { "Project title must be nonempty, at most 80 characters and contain no path separators." }
            val matching = if (chosenHomePath != null) {
                listOf(requireNotNull(session.homes.firstOrNull { it.path == chosenHomePath }) { "Chosen home is no longer available. Rebuild the review." })
            } else session.homes.filter { home ->
                home.name.equals(title, true) || home.aliases.any { it.equals(title, true) }
            }.distinctBy { it.path }
            require(matching.size <= 1) { "More than one home matches this title. Choose a distinct project title or resolve the duplicate homes first." }
            val home = matching.singleOrNull() ?: ProjectHomeCandidate(
                title, session.root.rawValue().trimEnd('/') + if (session.root is FileRef.Direct) "/Documents/$title" else "/$title",
                hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES,
                roleFolders = settingsRepository.hierarchyTemplate.first().roleFolders,
            )
            val result = withContext(Dispatchers.Default) { FilingAssignments.assign(session.result, sourceRefs, home, role, releaseFolder) }
            val plan = withContext(Dispatchers.Default) {
                if (session.root is FileRef.Direct) InboxFilingPlanAdapter.build(result, session.root, session.existingDirectories, session.retainedUncertainSourceRefs)
                else InboxFilingSafPlanAdapter.build(result, session.root, session.existingSafHomes, current.scopes.first().label, session.retainedUncertainSourceRefs)
            }
            showPlanPreview(
                current.goal, plan.operations, current.scopes, current.scopeNotes,
                plan.authorizedDestinationRoots, plan.defaultSelectedSourceRefs,
                plan.presentation.copy(reviewingUncertain = current.filingPresentation.reviewingUncertain),
                previousReviewedSources = current.reviewedSources,
            )
            val edited = _uiState.value as? ScanUiState.PlanPreview ?: return@launch
            // Preserve previous deselections; only explicitly assigned sources are newly selected.
            val formerlySelectedRefs = current.selectedIndices.mapNotNullTo(hashSetOf()) { index ->
                current.accepted.getOrNull(index)?.let(com.pocketsteward.app.plan.ReviewedSources::sourceOf)?.rawValue()
            }
            _uiState.value = edited.copy(selectedIndices = defaultSelectionForSources(edited.accepted, formerlySelectedRefs + sourceRefs))
            filingSession = session.copy(result = result, homes = (session.homes + home).distinctBy { it.path })
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: "Could not apply the project assignment.")
        }
    }
}
