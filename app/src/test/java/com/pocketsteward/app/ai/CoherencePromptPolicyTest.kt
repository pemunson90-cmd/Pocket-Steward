package com.pocketsteward.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoherencePromptPolicyTest {
    private fun documents(count: Int, excerptChars: Int = 2_000): List<SemanticDocument> =
        (1..count).map { index ->
            SemanticDocument(
                id = "/storage/emulated/0/Download/doc-$index.txt",
                displayName = "doc-$index.txt",
                sourcePath = "/storage/emulated/0/Download/doc-$index.txt",
                excerpt = "x".repeat(excerptChars),
            )
        }

    @Test
    fun initialWindowCapsExcerptWithoutChangingIdentityOrOrder() {
        val source = documents(24)

        val compact = CoherencePromptPolicy.compact(
            source,
            CoherencePromptPolicy.initialWindow(),
        )

        assertThat(compact).hasSize(20)
        assertThat(compact.first().id).isEqualTo(source.first().id)
        assertThat(compact.last().id).isEqualTo(source[19].id)
        assertThat(compact).allMatch { it.excerpt.length == 700 }
    }

    @Test
    fun nextShrinksExcerptBeforeDroppingDocuments() {
        val initial = CoherencePromptPolicy.initialWindow()
        val next = CoherencePromptPolicy.next(initial)!!

        assertThat(next.maxDocuments).isEqualTo(initial.maxDocuments)
        assertThat(next.excerptChars).isLessThan(initial.excerptChars)
    }

    @Test
    fun conservativeWindowIsSmallWhenTokenCountingIsUnavailable() {
        val compact = CoherencePromptPolicy.conservative(documents(20))

        assertThat(compact).hasSize(8)
        assertThat(compact).allMatch { it.excerpt.length == 400 }
    }

    @Test
    fun retryWindowReducesComputePressure() {
        val retry = CoherencePromptPolicy.retry(documents(20))

        assertThat(retry).hasSize(6)
        assertThat(retry).allMatch { it.excerpt.length == 320 }
    }

    @Test
    fun computeFailureDetectionMatchesObservedAicoreError() {
        assertThat(
            CoherencePromptPolicy.isComputeFailure(
                "[ErrorCode 5] AICore failed with error type 2-INFERENCE_ERROR " +
                    "and error code 5-COMPUTE_ERROR: Inference failed.",
            ),
        ).isTrue()
        assertThat(CoherencePromptPolicy.isComputeFailure("Some unrelated failure")).isFalse()
    }
}
