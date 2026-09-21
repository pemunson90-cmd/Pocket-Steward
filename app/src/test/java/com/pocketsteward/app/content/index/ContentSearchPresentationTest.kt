package com.pocketsteward.app.content.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentSearchPresentationTest {
    @Test
    fun filterLabelOnlyShowsCountWhenFiltersAreActive() {
        assertThat(ContentSearchPresentation.filtersLabel(0)).isEqualTo("Filters")
        assertThat(ContentSearchPresentation.filtersLabel(3)).isEqualTo("Filters (3)")
    }

    @Test
    fun snippetsAreVisiblyQuotedAndTrimmed() {
        assertThat(ContentSearchPresentation.quotedSnippet("  pain in context  "))
            .isEqualTo("“pain in context”")
    }
}
