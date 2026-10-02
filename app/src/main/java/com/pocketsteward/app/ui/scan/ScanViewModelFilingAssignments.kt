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
    val manualAssignments: Map<String, com.pocketsteward.app.filing.FilingDecision>? = null,
    val reviewId: String? = null,
    val originalSources: Map<String, com.pocketsteward.app.plan.SourcePrecondition>? = null,
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
            val nextSession = session.copy(result = result, homes = (session.homes + home).distinctBy { it.path },
                manualAssignments = session.manualAssignments.orEmpty() + result.decisions.filter { it.artifact.stableRef in sourceRefs }.associateBy { it.artifact.stableRef },
                reviewId = java.util.UUID.randomUUID().toString())
            showPlanPreview(
                current.goal, plan.operations, current.scopes, current.scopeNotes,
                plan.authorizedDestinationRoots, plan.defaultSelectedSourceRefs,
                plan.presentation.copy(reviewingUncertain = current.filingPresentation.reviewingUncertain, imageCoverage = current.filingPresentation.imageCoverage, reviewSessionId = nextSession.reviewId,
                    heldSourceRefs = current.filingPresentation.heldSourceRefs.orEmpty() - sourceRefs,
                    indexedFolderDescendantCount = current.filingPresentation.indexedFolderDescendantCount),
                previousReviewedSources = session.originalSources.orEmpty() + current.reviewedSources,
            )
            val edited = _uiState.value as? ScanUiState.PlanPreview ?: return@launch
            // Preserve previous deselections; only explicitly assigned sources are newly selected.
            val formerlySelectedRefs = current.selectedIndices.mapNotNullTo(hashSetOf()) { index ->
                current.accepted.getOrNull(index)?.let(com.pocketsteward.app.plan.ReviewedSources::sourceOf)?.rawValue()
            }
            _uiState.value = edited.copy(selectedIndices = defaultSelectionForSources(edited.accepted, formerlySelectedRefs + sourceRefs))
            filingSession = nextSession

        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: "Could not apply the project assignment.")
        }
    }
}

internal fun ScanViewModel.keepFilingFiles(sourceRefs: Set<String>) {
    if (hasActiveFilingWork || busy.value != null) return
    val current = _preview.value ?: return
    val filing = current.filingPresentation ?: return
    if (!ReviewDraftPolicy.matchesFilingSession(current, filingSession)) return
    val allowed = (filing.groups.flatMap { it.items } + filing.unresolved).mapTo(hashSetOf()) { it.sourceRef }
    if (sourceRefs.any { it !in allowed }) {
        _error.value = "This review changed. Make your Keep choices in the current review."
        return
    }
    val selectedRefs = current.selectedIndices.mapNotNullTo(hashSetOf()) { index ->
        current.accepted.getOrNull(index)?.let(com.pocketsteward.app.plan.ReviewedSources::sourceOf)?.rawValue()
    }
    _preview.value = current.copy(
        selectedIndices = defaultSelectionForSources(current.accepted, selectedRefs - sourceRefs),
        filingPresentation = filing.copy(heldSourceRefs = filing.heldSourceRefs.orEmpty() + sourceRefs),
    )
}

internal fun ScanViewModel.confirmFilingFiles(sourceRefs: Set<String>) {
    if (hasActiveFilingWork || busy.value != null) return
    val current = _preview.value ?: return
    val filing = current.filingPresentation ?: return
    if (!ReviewDraftPolicy.matchesFilingSession(current, filingSession)) return
    val selectedRefs = current.selectedIndices.mapNotNullTo(hashSetOf()) { index ->
        current.accepted.getOrNull(index)?.let(com.pocketsteward.app.plan.ReviewedSources::sourceOf)?.rawValue()
    }
    _preview.value = current.copy(selectedIndices = defaultSelectionForSources(current.accepted, selectedRefs + sourceRefs),
        filingPresentation = filing.copy(heldSourceRefs = filing.heldSourceRefs.orEmpty() - sourceRefs))
}

internal fun ScanViewModel.deferFilingFiles(sourceRefs: Set<String>) {
    if (hasActiveFilingWork || busy.value != null || sourceRefs.isEmpty()) return
    val session = filingSession ?: return
    val current = _preview.value ?: return
    val filing = current.filingPresentation ?: return
    if (!ReviewDraftPolicy.matchesFilingSession(current, session)) return
    filingEditJob = viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Updating Uncertain plan", "Checking destinations before Run")
        try {
            val result = withContext(Dispatchers.Default) { com.pocketsteward.app.filing.FilingDispositionPolicy.defer(session.result, sourceRefs) }
            val plan = withContext(Dispatchers.Default) {
                if (session.root is FileRef.Direct) InboxFilingPlanAdapter.build(result, session.root, session.existingDirectories, session.retainedUncertainSourceRefs)
                else InboxFilingSafPlanAdapter.build(result, session.root, session.existingSafHomes, current.scopes.first().label, session.retainedUncertainSourceRefs)
            }
            val nextSession = session.copy(result = result,
                manualAssignments = session.manualAssignments.orEmpty() + result.decisions.filter { it.artifact.stableRef in sourceRefs }.associateBy { it.artifact.stableRef },
                reviewId = java.util.UUID.randomUUID().toString())
            showPlanPreview(current.goal, plan.operations, current.scopes, current.scopeNotes,
                plan.authorizedDestinationRoots, plan.defaultSelectedSourceRefs,
                plan.presentation.copy(reviewingUncertain = filing.reviewingUncertain, imageCoverage = filing.imageCoverage,
                    reviewSessionId = nextSession.reviewId, heldSourceRefs = filing.heldSourceRefs.orEmpty() - sourceRefs,
                    indexedFolderDescendantCount = filing.indexedFolderDescendantCount),
                previousReviewedSources = session.originalSources.orEmpty() + current.reviewedSources)
            val edited = _uiState.value as? ScanUiState.PlanPreview ?: return@launch
            val selectedRefs = current.selectedIndices.mapNotNullTo(hashSetOf()) { index ->
                current.accepted.getOrNull(index)?.let(com.pocketsteward.app.plan.ReviewedSources::sourceOf)?.rawValue()
            }
            _uiState.value = edited.copy(selectedIndices = defaultSelectionForSources(edited.accepted, selectedRefs + sourceRefs))
            filingSession = nextSession
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: "Could not defer these files.")
        }
    }
}
