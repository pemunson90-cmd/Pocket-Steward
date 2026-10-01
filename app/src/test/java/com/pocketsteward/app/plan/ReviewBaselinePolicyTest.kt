package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class ReviewBaselinePolicyTest {
    private val operation = PlannedOperation.Move(FileRef.Direct("/Downloads/a.txt"), FileRef.Direct("/Documents/a.txt"), "Assigned")
    private val key = "/Downloads/a.txt"
    private val old = SourcePrecondition(20, 100)
    @Test fun unresolvedBaselineSurvivesAssignmentAndChangedSourceIsRejected() {
        val baseline = ReviewBaselinePolicy.merge(emptyList(), emptyMap(), mapOf(key to old))
        assertThat(baseline[key]).isEqualTo(old)
        assertThat(runCatching { ReviewBaselinePolicy.merge(listOf(operation), mapOf(key to old.copy(modifiedAtEpochMs = 200)), baseline) }.isFailure).isTrue()
    }
    @Test fun missingOrChangedTypeCannotAcquireNewBaseline() {
        assertThat(runCatching { ReviewBaselinePolicy.merge(listOf(operation), emptyMap(), mapOf(key to old)) }.isFailure).isTrue()
        assertThat(runCatching { ReviewBaselinePolicy.merge(listOf(operation), mapOf(key to old.copy(directoryDigest = "new-folder")), mapOf(key to old)) }.isFailure).isTrue()
    }
    @Test fun unchangedSourcesKeepOriginalBaselineAndUnrelatedNewSourcesAreAdded() {
        val current = mapOf(key to old, "/Downloads/b.txt" to SourcePrecondition(30, 300))
        assertThat(ReviewBaselinePolicy.merge(listOf(operation), current, mapOf(key to old))).isEqualTo(current)
    }
}
