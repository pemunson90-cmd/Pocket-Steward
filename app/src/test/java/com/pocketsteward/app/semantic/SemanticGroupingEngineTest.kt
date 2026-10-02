package com.pocketsteward.app.semantic

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.ai.CoherenceClass
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.rules.ProjectKeyword
import org.junit.Test

class SemanticGroupingEngineTest {
    @Test fun scopedCorrectionUsesSharedPrecedenceAndRequiresItsExactConfiguredHome() {
        val record = record("/Download/Lilith.md", "Lilith.md")
        val rules = listOf(com.pocketsteward.app.saved.CorrectionRule("Lilith", "Elsewhere"),
            com.pocketsteward.app.saved.CorrectionRule("Lilith", "Lilith", "/Download", "/Documents/Lilith"))
        val model = listOf(SemanticSuggestion(record.stableRef, CoherenceClass.QUESTIONABLE, "Guess"))
        assertThat(SemanticGroupingEngine.decide(listOf(record), emptyList(), rules, emptyMap(), model,
            mapOf(record.stableRef to "/Documents")).single().suggestion.suggestedGroup).isEqualTo("Lilith")
        assertThat(SemanticGroupingEngine.decide(listOf(record), emptyList(), rules, emptyMap(), model,
            mapOf(record.stableRef to "/Other"))).isEmpty()
    }
    @Test fun conflictingLearnedMappingsStayUnresolvedInsteadOfChoosingTheFirst() {
        val record = record("/Download/Lilith_NSTL.md", "Lilith_NSTL.md")
        val decisions = SemanticGroupingEngine.decide(listOf(record), emptyList(),
            listOf(com.pocketsteward.app.saved.CorrectionRule("Lilith", "Lilith"),
                com.pocketsteward.app.saved.CorrectionRule("NSTL", "NSTL")), emptyMap(),
            listOf(SemanticSuggestion(record.stableRef, CoherenceClass.QUESTIONABLE, "Model guess")))
        assertThat(decisions).isEmpty()
    }

    @Test fun conflictingContentKeywordsDoNotYieldAnArbitraryModelDestination() {
        val record = record("/Download/document.md", "document.md")
        val decisions = SemanticGroupingEngine.decide(listOf(record),
            listOf(ProjectKeyword("Lilith", "Lilith"), ProjectKeyword("NSTL", "NSTL")),
            indexedTextByRef = mapOf(record.stableRef to "Comparison of Lilith and NSTL"),
            modelSuggestions = listOf(SemanticSuggestion(record.stableRef, CoherenceClass.QUESTIONABLE, "Model guess")))
        assertThat(decisions).isEmpty()
    }

    @Test fun genericExportNamesDoNotInventAProjectCluster() {
        val records = (1..20).map { record("/Download/ChatGPT_export_$it.md", "ChatGPT_export_$it.md") }
        assertThat(SemanticGroupingEngine.decide(records, emptyList(), indexedTextByRef = emptyMap(), modelSuggestions = emptyList()))
            .isEmpty()
    }

    @Test
    fun explicitProjectKeywordWinsOverFilenameClusterAndModel() {
        val records = listOf(
            record("/Download/lilith_notes_1.md", "lilith_notes_1.md"),
            record("/Download/lilith_notes_2.md", "lilith_notes_2.md"),
            record("/Download/lilith_notes_3.md", "lilith_notes_3.md"),
        )

        val decisions = SemanticGroupingEngine.decide(
            records = records,
            projectKeywords = listOf(ProjectKeyword("Lilith", "Project Lilith")),
            indexedTextByRef = emptyMap(),
            modelSuggestions = listOf(
                SemanticSuggestion(
                    stableRef = records.first().stableRef,
                    classification = CoherenceClass.DOES_NOT_BELONG,
                    suggestedGroup = "Wrong Model Group",
                ),
            ),
        )

        assertThat(decisions.first().suggestion.suggestedGroup).isEqualTo("Project Lilith")
        assertThat(decisions.first().evidence).isEqualTo(SemanticEvidence.PROJECT_KEYWORD_FILENAME)
    }

    @Test
    fun repeatedFilenameTitleCreatesOneLevelGroup() {
        val records = listOf(
            record("/Download/lease_world_notes.md", "lease_world_notes.md"),
            record("/Download/lease_world_outline.md", "lease_world_outline.md"),
            record("/Download/other.txt", "other.txt"),
        )

        val decisions = SemanticGroupingEngine.decide(
            records = records,
            projectKeywords = emptyList(),
            indexedTextByRef = emptyMap(),
            modelSuggestions = emptyList(),
        )

        assertThat(decisions.map { it.suggestion.suggestedGroup })
            .containsAtLeast("Lease World", "Lease World")
    }

    @Test
    fun indexedProjectKeywordBeatsModelAdviceWhenFilenameHasNoSignal() {
        val record = record("/Download/x7q2.md", "x7q2.md")
        val decisions = SemanticGroupingEngine.decide(
            records = listOf(record),
            projectKeywords = listOf(ProjectKeyword("NSTL", "Nobody Signed the Lease")),
            indexedTextByRef = mapOf(record.stableRef to "This is an NSTL continuity note."),
            modelSuggestions = listOf(
                SemanticSuggestion(
                    stableRef = record.stableRef,
                    classification = CoherenceClass.QUESTIONABLE,
                    suggestedGroup = "Misc",
                ),
            ),
        )

        assertThat(decisions.single().suggestion.suggestedGroup).isEqualTo("Nobody Signed the Lease")
        assertThat(decisions.single().evidence).isEqualTo(SemanticEvidence.PROJECT_KEYWORD_CONTENT)
    }

    private fun record(ref: String, name: String) = FileRecord(
        stableRef = ref,
        displayName = name,
        extension = name.substringAfterLast('.', ""),
        mimeType = null,
        absolutePathOrUri = ref,
        parentRef = "/Download",
        sizeBytes = 10,
        createdAt = null,
        modifiedAt = 1,
        lastScannedAt = 1,
        isDirectory = false,
        isHidden = false,
    )
}
