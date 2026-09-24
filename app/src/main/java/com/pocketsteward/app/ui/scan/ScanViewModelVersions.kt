package com.pocketsteward.app.ui.scan

import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.versions.VersionChains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Version chains: "Resume.pdf", "Resume (1).pdf", "Resume_v2.pdf" found by
// name alone, then an ordinary plan that moves the older ones into an
// "Older versions" folder beside the newest. Same find/propose shape as
// duplicates; nothing moves until the preview is approved.

internal fun ScanViewModel.findVersionChains(summary: ScanUiState.Summary) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Finding older versions", "Comparing file names")
        try {
            val chains = withContext(Dispatchers.Default) {
                VersionChains.detect(allRecordsForScopes(summary.scopes))
            }
            _uiState.value = ScanUiState.VersionChainReview(chains, summary.scopes)
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}

internal fun ScanViewModel.proposeOlderVersions(review: ScanUiState.VersionChainReview) {
    viewModelScope.launch {
        _uiState.value = ScanUiState.Working("Planning", "Building the older-versions plan")
        try {
            val records = allRecordsForScopes(review.scopes)
            val operations = VersionChains.planOperations(review.chains, records)
            if (operations.isEmpty()) {
                _uiState.value = ScanUiState.Error("No older versions to move.")
                return@launch
            }
            showPlanPreview(
                goal = "Move older versions aside under ${review.scopeLabel}",
                operations = operations,
                scopes = review.scopes,
                scopeNotes = listOf(
                    "The newest file in each set stays where it is. Older ones move into " +
                        "\"${VersionChains.OLDER_VERSIONS_FOLDER}\" in the same folder. Nothing is trashed.",
                ),
            )
        } catch (t: Throwable) {
            _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
        }
    }
}
