package com.pocketsteward.app.similarity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimilarityEngineTest {
    @Test
    fun imageHashesWithinThresholdGroup() {
        val groups = SimilarityEngine.group(
            listOf(
                SimilaritySignature("a", SimilarityKind.IMAGE, 0L),
                SimilaritySignature("b", SimilarityKind.IMAGE, 0b1111L),
                SimilaritySignature("c", SimilarityKind.IMAGE, -1L),
            ),
            imageThreshold = 4,
        )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().stableRefs).containsExactly("a", "b")
    }

    @Test
    fun differentKindsNeverGroup() {
        val groups = SimilarityEngine.group(
            listOf(
                SimilaritySignature("a", SimilarityKind.IMAGE, 0L),
                SimilaritySignature("b", SimilarityKind.DOCUMENT, 0L),
            ),
        )
        assertThat(groups).isEmpty()
    }

    @Test
    fun documentSimHashTracksSimilarText() {
        val base = DocumentSimHash.of(
            "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu project continuity notes",
        )!!
        val near = DocumentSimHash.of(
            "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu project continuity outline",
        )!!
        val far = DocumentSimHash.of(
            "cooking recipe flour butter sugar eggs oven dinner kitchen garlic cheese tomato basil",
        )!!

        assertThat(java.lang.Long.bitCount(base xor near))
            .isLessThan(java.lang.Long.bitCount(base xor far))
    }
}
