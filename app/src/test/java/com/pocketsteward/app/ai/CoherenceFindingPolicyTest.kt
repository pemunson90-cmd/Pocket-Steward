package com.pocketsteward.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoherenceFindingPolicyTest {
    @Test fun duplicateOutputHasOnlyOneReviewIdentity() {
        val finding = CoherenceFinding("a", CoherenceClass.BELONGS, "Fits", null)
        assertThat(CoherenceFindingPolicy.forBatch(listOf(finding, finding), setOf("a"))).containsExactly(finding)
    }
    @Test fun conflictingDuplicatesCannotAuthorizeRegrouping() {
        val a = CoherenceFinding("a", CoherenceClass.BELONGS, "Fits", null)
        val b = a.copy(classification = CoherenceClass.DOES_NOT_BELONG, suggestedGroup = "Other")
        val result = CoherenceFindingPolicy.forBatch(listOf(a, b), setOf("a")).single()
        assertThat(result.classification).isEqualTo(CoherenceClass.UNCERTAIN)
        assertThat(result.suggestedGroup).isNull()
    }
    @Test fun outputCannotNameAnotherBatchDocument() {
        assertThat(CoherenceFindingPolicy.forBatch(listOf(CoherenceFinding("other", CoherenceClass.BELONGS, "Fits", null)), setOf("a"))).isEmpty()
    }
}
