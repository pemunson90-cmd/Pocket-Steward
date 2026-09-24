package com.pocketsteward.app.content.ask

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AskRetrievalTest {
    private fun row(id: Long, ref: String, body: String, page: Int? = null, name: String = ref.substringAfterLast('/')) =
        AskCandidateRow(id, ref, name, ref.substringBeforeLast('/'), name.substringAfterLast('.'), page, false, body)

    @Test
    fun keywordsDropQuestionWordsAndKeepTheTellingOnes() {
        assertThat(AskRetrieval.keywords("When does my lease end, and what's the deposit?"))
            .containsExactly("deposit", "lease", "end").inOrder()
        assertThat(AskRetrieval.keywords("What does Pat's contract say about overtime?"))
            .containsExactly("overtime", "contract", "pat")
    }

    @Test
    fun ftsQueryIsPrefixOrAndCannotCarryOperators() {
        assertThat(AskRetrieval.ftsQuery(listOf("lease", "deposit"))).isEqualTo("lease* OR deposit*")
        assertThat(AskRetrieval.ftsQuery(listOf("o'brien", "rent"))).isEqualTo("rent*")
        assertThat(AskRetrieval.ftsQuery(emptyList())).isNull()
    }

    @Test
    fun passagesMatchingMoreKeywordsRankFirstAndOneFileCannotCrowdOutOthers() {
        val rows = listOf(
            row(1, "/sd/Lease.pdf", "The lease ends on 31 May 2027.", page = 1),
            row(2, "/sd/Lease.pdf", "lease lease lease", page = 2),
            row(3, "/sd/Lease.pdf", "lease again", page = 3),
            row(4, "/sd/Letter.txt", "Your deposit and lease terms are enclosed."),
            row(5, "/sd/Other.txt", "nothing relevant"),
        )
        val passages = AskRetrieval.rank(rows, listOf("deposit", "lease"), maxPassages = 6, perFile = 2)
        assertThat(passages.first().displayName).isEqualTo("Letter.txt")
        assertThat(passages.count { it.displayName == "Lease.pdf" }).isEqualTo(2)
        assertThat(passages.none { it.displayName == "Other.txt" }).isTrue()
        assertThat(passages.map { it.number }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun windowCentresOnTheHitsAndMarksWhatWasCut() {
        val filler = "word ".repeat(400)
        val body = filler + "the security deposit is 1200 dollars" + " end".repeat(300)
        val out = AskRetrieval.window(body, listOf("deposit"), maxChars = 200)
        assertThat(out).contains("security deposit is 1200")
        assertThat(out).startsWith("… ")
        assertThat(out).endsWith(" …")
        assertThat(out.length).isAtMost(206)
    }

    @Test
    fun shortBodiesComeBackWholeWithTidiedWhitespace() {
        assertThat(AskRetrieval.window("a  b\n\nc", listOf("b"), 100)).isEqualTo("a b c")
    }
}
