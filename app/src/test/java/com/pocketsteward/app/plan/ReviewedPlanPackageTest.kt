package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class ReviewedPlanPackageTest {
    @Test
    fun reviewedPlanRoundTripsTypedOperations() {
        val operations = listOf(
            PlannedOperation.CreateDirectory(FileRef.Direct("/Download"), "Docs", "reason"),
            PlannedOperation.Move(
                FileRef.Direct("/Download/a.pdf"),
                FileRef.Direct("/Download/Docs/a.pdf"),
                "move",
            ),
        )
        val json = ReviewedPlanPackage.encode("Organize docs", operations)
        val decoded = ReviewedPlanPackage.decodeOrNull(json)!!

        assertThat(decoded.goal).isEqualTo("Organize docs")
        assertThat(decoded.operations).containsExactlyElementsIn(operations).inOrder()
    }

    @Test
    fun unrelatedJsonIsRejected() {
        assertThat(ReviewedPlanPackage.decodeOrNull("{\"hello\":\"world\"}")).isNull()
    }
}
