package com.pocketsteward.app.ui.scan

/** Publish only the fields produced by background work; retain newer user choices. */
internal fun mergeIndexedSearchUpdate(
    captured: ScanUiState.IndexedContentSearchReview,
    latest: ScanUiState?,
    update: ScanUiState.IndexedContentSearchReview,
): ScanUiState? = if (latest is ScanUiState.IndexedContentSearchReview &&
    latest.query == captured.query && latest.scopes == captured.scopes
) latest.copy(
    allResults = update.allResults,
    refreshSummary = update.refreshSummary,
    indexStates = update.indexStates,
    indexJobs = update.indexJobs,
) else latest

/** An edited plan must still belong to the review that was validated. */
internal fun planEditPublicationBase(
    captured: ScanUiState.PlanPreview,
    latest: ScanUiState.PlanPreview?,
): ScanUiState.PlanPreview = latest?.takeIf { it.accepted === captured.accepted }
    ?: error("This review changed during the edit. Open the current review and try again.")
