package com.pocketsteward.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AskAnswerProtocolTest {
    @Test
    fun keepsACitedAnswerAndItsCitationsInOrder() {
        val a = AskAnswerProtocol.parse("The lease ends 31 May 2027 [2]. The deposit is 1200 [1].", 3)
        assertThat(a).isEqualTo(
            AskModelAnswer.Answered("The lease ends 31 May 2027 [2]. The deposit is 1200 [1].", listOf(2, 1)),
        )
    }

    @Test
    fun dropsCitationsToPassagesThatDoNotExist() {
        val a = AskAnswerProtocol.parse("Rent is 900 [1][7]. Due on the 1st [9].", 2) as AskModelAnswer.Answered
        assertThat(a.citations).containsExactly(1)
        assertThat(a.text).isEqualTo("Rent is 900 [1][]. Due on the 1st.".replace("[1][]", "[1]"))
    }

    @Test
    fun splitsCommaListsOfCitations() {
        val a = AskAnswerProtocol.parse("Both mention it [1, 3].", 3) as AskModelAnswer.Answered
        assertThat(a.citations).containsExactly(1, 3).inOrder()
        assertThat(a.text).isEqualTo("Both mention it [1][3].")
    }

    @Test
    fun anUncitedAnswerIsRejected() {
        assertThat(AskAnswerProtocol.parse("The lease ends in May.", 3)).isNull()
        assertThat(AskAnswerProtocol.parse("", 3)).isNull()
    }

    @Test
    fun notFoundIsRecognised() {
        assertThat(AskAnswerProtocol.parse("NOT_FOUND", 3)).isEqualTo(AskModelAnswer.NotFound)
        assertThat(AskAnswerProtocol.parse("not found", 3)).isEqualTo(AskModelAnswer.NotFound)
    }
}
