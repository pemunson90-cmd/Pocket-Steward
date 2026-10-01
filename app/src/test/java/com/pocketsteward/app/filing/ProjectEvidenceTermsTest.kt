package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectEvidenceTermsTest {
    @Test fun genericExportsDoNotCreateAGlobalProjectRule() {
        assertThat(ProjectEvidenceTerms.learnFilenameTerm(listOf("ChatGPT_export_1.txt", "ChatGPT_export_2.txt"))).isNull()
        assertThat(ProjectEvidenceTerms.learnFilenameTerm(listOf("chapter_1_manuscript.md", "chapter_2_manuscript.md"))).isNull()
    }

    @Test fun aDistinctProjectTermSurvivesGenericExportWords() {
        assertThat(ProjectEvidenceTerms.learnFilenameTerm(listOf("ChatGPT_NSTL_export_1.txt", "NSTL_notes_2.md")))
            .isEqualTo("nstl")
    }

    @Test fun splitProjectBatchesDoNotLearnAnArbitraryMajority() {
        assertThat(ProjectEvidenceTerms.learnFilenameTerm(listOf("Lilith_notes.md", "Lilith_draft.md", "Lilith_cover.jpg",
            "NSTL_notes.md", "NSTL_draft.md"))).isNull()
    }

    @Test fun ambiguousCommonTermsDoNotBreakTiesByWordLength() {
        assertThat(ProjectEvidenceTerms.learnFilenameTerm(listOf("Lilith_NSTL_notes.md", "Lilith_NSTL_draft.md"))).isNull()
    }

    @Test fun versionsAndOpaqueIdsAreNotProjectNames() {
        assertThat(ProjectEvidenceTerms.filenameTokens("export_v12_rc4_9ab8ce17.txt")).isEmpty()
    }

    @Test fun mappingsMatchWordsAcrossFilenameSeparatorsWithoutMatchingSubstrings() {
        assertThat(ProjectEvidenceTerms.containsTerm("NSTL_notes.md", "nstl")).isTrue()
        assertThat(ProjectEvidenceTerms.containsTerm("A Lilith manuscript", "Lilith")).isTrue()
        assertThat(ProjectEvidenceTerms.containsTerm("history.txt", "story")).isFalse()
        assertThat(ProjectEvidenceTerms.containsTerm("Lilithian_notes.md", "Lilith")).isFalse()
        assertThat(ProjectEvidenceTerms.containsTerm("file.txt", "")).isFalse()
    }
}
