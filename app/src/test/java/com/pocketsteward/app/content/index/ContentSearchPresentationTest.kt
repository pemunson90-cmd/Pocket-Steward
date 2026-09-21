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
    @Test
    fun mimeMappingCoversPreviewedDocumentTypes() {
        assertThat(ContentSearchPresentation.mimeType("PDF")).isEqualTo("application/pdf")
        assertThat(ContentSearchPresentation.mimeType("md")).isEqualTo("text/plain")
        assertThat(ContentSearchPresentation.mimeType("docx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertThat(ContentSearchPresentation.mimeType("weird")).isEqualTo("*/*")
    }

}
