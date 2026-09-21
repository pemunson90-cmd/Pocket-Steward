package com.pocketsteward.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoherenceTextProtocolTest {
    private val ids = mapOf(
        "D0001" to "/storage/emulated/0/Download/a.md",
        "D0002" to "/storage/emulated/0/Download/b.pdf",
    )

    @Test
    fun parsesValidRecordsAndIgnoresChatter() {
        val output = """
            Here is the audit.
            PSF	D0001	BELONGS	-	Matches the folder theme.
            nonsense
            PSF	D0002	QUESTIONABLE	Receipts	Looks financial rather than narrative.
        """.trimIndent()

        val result = CoherenceTextProtocol.parse(output, ids)

        assertThat(result).containsExactly(
            CoherenceTextProtocol.ParsedFinding(
                "/storage/emulated/0/Download/a.md",
                CoherenceClass.BELONGS,
                null,
                "Matches the folder theme.",
            ),
            CoherenceTextProtocol.ParsedFinding(
                "/storage/emulated/0/Download/b.pdf",
                CoherenceClass.QUESTIONABLE,
                "Receipts",
                "Looks financial rather than narrative.",
            ),
        ).inOrder()
    }

    @Test
    fun acceptsPipeDelimitedRecordsFromNanoFormatting() {
        val result = CoherenceTextProtocol.parse(
            "PSF|D0001|BELONGS|-|Matches the folder theme.",
            ids,
        ).single()

        assertThat(result.documentId).isEqualTo(ids.getValue("D0001"))
        assertThat(result.classification).isEqualTo(CoherenceClass.BELONGS)
        assertThat(result.suggestedGroup).isNull()
    }

    @Test
    fun acceptsMarkdownTableEdgePipesWithoutAcceptingArbitraryProse() {
        val output = """
            | PSF | D0002 | QUESTIONABLE | Receipts | Looks financial. |
            explanation PSF | D0001 | BELONGS | - | should not parse
        """.trimIndent()

        val result = CoherenceTextProtocol.parse(output, ids)

        assertThat(result).containsExactly(
            CoherenceTextProtocol.ParsedFinding(
                ids.getValue("D0002"),
                CoherenceClass.QUESTIONABLE,
                "Receipts",
                "Looks financial.",
            ),
        )
    }


    @Test
    fun singleDocumentParserAcceptsExactPipeResponse() {
        val result = CoherenceTextProtocol.parseSingle(
            "QUESTIONABLE|Receipts|Looks financial rather than narrative.",
            ids.getValue("D0001"),
        )

        assertThat(result).isEqualTo(
            CoherenceTextProtocol.ParsedFinding(
                ids.getValue("D0001"),
                CoherenceClass.QUESTIONABLE,
                "Receipts",
                "Looks financial rather than narrative.",
            ),
        )
    }

    @Test
    fun singleDocumentParserToleratesHarmlessProseAroundClosedEnum() {
        val result = CoherenceTextProtocol.parseSingle(
            "Classification: DOES NOT BELONG because this is unrelated.",
            ids.getValue("D0002"),
        )

        assertThat(result!!.classification).isEqualTo(CoherenceClass.DOES_NOT_BELONG)
        assertThat(result.documentId).isEqualTo(ids.getValue("D0002"))
    }

    @Test
    fun singleDocumentParserRejectsResponsesWithoutClosedClassification() {
        assertThat(
            CoherenceTextProtocol.parseSingle(
                "I am not sure what to call this.",
                ids.getValue("D0001"),
            ),
        ).isNull()
    }

    @Test
    fun unknownAliasIsIgnored() {
        val result = CoherenceTextProtocol.parse(
            "PSF	D9999	BELONGS	-	Nope",
            ids,
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun duplicateAliasIsDiscardedEntirely() {
        val output = """
            PSF	D0001	BELONGS	-	one
            PSF	D0001	DOES_NOT_BELONG	Other	two
        """.trimIndent()

        assertThat(CoherenceTextProtocol.parse(output, ids)).isEmpty()
    }

    @Test
    fun invalidClassificationBecomesUncertain() {
        val result = CoherenceTextProtocol.parse(
            "PSF	D0001	ABSOLUTELY_MAYBE	-	reason",
            ids,
        ).single()

        assertThat(result.classification).isEqualTo(CoherenceClass.UNCERTAIN)
    }

    @Test
    fun malformedRecordIsIgnored() {
        val output = """
            PSF	D0001	BELONGS
            PSF|D0001|BELONGS|missing reason
        """.trimIndent()

        assertThat(CoherenceTextProtocol.parse(output, ids)).isEmpty()
    }

    @Test
    fun reasonMayContainAdditionalTabsOnlyIfProtocolKeepsFiveFields() {
        val result = CoherenceTextProtocol.parse(
            "PSF	D0001	BELONGS	-	reason	with	words",
            ids,
        ).single()

        assertThat(result.reason).isEqualTo("reason	with	words")
    }

    @Test
    fun groupAndReasonAreBounded() {
        val group = "g".repeat(200)
        val reason = "r".repeat(1000)
        val result = CoherenceTextProtocol.parse(
            "PSF	D0001	QUESTIONABLE	$group	$reason",
            ids,
        ).single()

        assertThat(result.suggestedGroup!!.length).isEqualTo(80)
        assertThat(result.reason.length).isEqualTo(400)
    }

    @Test
    fun dashOrBlankGroupMeansNoSuggestion() {
        val dash = CoherenceTextProtocol.parse(
            "PSF	D0001	BELONGS	-	reason",
            ids,
        ).single()
        val blank = CoherenceTextProtocol.parse(
            "PSF	D0002	BELONGS		reason",
            ids,
        ).single()

        assertThat(dash.suggestedGroup).isNull()
        assertThat(blank.suggestedGroup).isNull()
    }
}
