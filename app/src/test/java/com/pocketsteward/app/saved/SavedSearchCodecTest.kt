package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.index.ContentSearchFilters
import com.pocketsteward.app.content.index.ContentSearchProvenance
import com.pocketsteward.app.content.index.ContentSearchSort
import org.junit.Test

class SavedSearchCodecTest {
    @Test
    fun roundTripsFullViewState() {
        val input = SavedSearch(
            id = "s1",
            name = "Pain docs",
            query = "pain",
            roots = listOf("/storage/Download", "/storage/Documents"),
            sort = ContentSearchSort.MODIFIED_NEWEST,
            filters = ContentSearchFilters(
                sourceRoots = setOf("/storage/Download"),
                categories = setOf("DOCUMENT"),
                extensions = setOf("pdf", "md"),
                provenance = ContentSearchProvenance.OCR,
                modifiedAfter = 10L,
                modifiedBefore = 20L,
                minSizeBytes = 100L,
                maxSizeBytes = 200L,
                pathContains = "NSTL/Chapter",
            ),
            lastResultCount = 476,
            lastOpenedAt = 1234L,
        )

        assertThat(SavedSearchCodec.decode(SavedSearchCodec.encode(listOf(input))))
            .containsExactly(input)
    }

    @Test
    fun malformedRecordDoesNotDestroyValidOnes() {
        val valid = SavedSearch(
            id = "s1",
            name = "x",
            query = "pain",
            roots = listOf("/a"),
            sort = ContentSearchSort.RELEVANCE,
            filters = ContentSearchFilters(),
            lastResultCount = 1,
            lastOpenedAt = 1,
        )
        val raw = "not-base64\n" + SavedSearchCodec.encode(listOf(valid))

        assertThat(SavedSearchCodec.decode(raw)).containsExactly(valid)
    }
}
