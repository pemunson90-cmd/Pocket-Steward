package com.pocketsteward.app.stabilization

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.index.ContentIndexRefreshSummary
import com.pocketsteward.app.content.index.ContentSearchFilters
import com.pocketsteward.app.content.index.ContentSearchSort
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.ui.scan.*
import org.junit.Test

class ReviewPublicationTest {
    private val scopes = listOf(ScanScope("Downloads", FileRef.Direct("/Downloads")))
    private fun search() = ScanUiState.IndexedContentSearchReview(
        "Lilith", "Lilith", scopes, emptySet(), emptyList(),
        ContentIndexRefreshSummary(0, 0, 0, 0, 0, 0), emptyList(),
    )

    @Test fun delayedIndexRefreshRetainsFiltersAndSortChangedDuringRead() {
        val captured = search()
        val latest = captured.copy(sort = ContentSearchSort.NAME_DESC, filters = ContentSearchFilters(extensions = setOf("txt")))
        val update = captured.copy(refreshSummary = ContentIndexRefreshSummary(9, 2, 7, 0, 0, 0))
        val published = mergeIndexedSearchUpdate(captured, latest, update) as ScanUiState.IndexedContentSearchReview
        assertThat(published.sort).isEqualTo(ContentSearchSort.NAME_DESC)
        assertThat(published.filters.extensions).containsExactly("txt")
        assertThat(published.refreshSummary.extracted).isEqualTo(7)
    }

    @Test fun delayedIndexRefreshCannotReplaceANewQueryOrDestination() {
        val captured = search()
        val newQuery = captured.copy(query = "NSTL")
        assertThat(mergeIndexedSearchUpdate(captured, newQuery, captured)).isSameInstanceAs(newQuery)
        val newScope = captured.copy(scopes = listOf(ScanScope("Documents", FileRef.Direct("/Documents"))))
        assertThat(mergeIndexedSearchUpdate(captured, newScope, captured)).isSameInstanceAs(newScope)
        assertThat(mergeIndexedSearchUpdate(captured, ScanUiState.Idle, captured)).isEqualTo(ScanUiState.Idle)
    }

    private fun plan() = ScanUiState.PlanPreview(
        "Move", listOf(PlannedOperation.Move(FileRef.Direct("/Downloads/a.txt"), FileRef.Direct("/Documents/a.txt"), "test")),
        emptyList(), scopes, listOf("Downloads"), selectedIndices = setOf(0),
    )

    @Test fun delayedDestinationEditRetainsTheLatestDeselection() {
        val captured = plan()
        val latest = captured.copy(selectedIndices = emptySet())
        assertThat(planEditPublicationBase(captured, latest).selectedIndices).isEmpty()
    }

    @Test(expected = IllegalStateException::class)
    fun delayedDestinationEditCannotReplaceAnotherPlan() {
        planEditPublicationBase(plan(), plan())
    }
}
