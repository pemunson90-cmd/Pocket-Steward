package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class ReviewedSourcesTest {
    private val reviewed = SourcePrecondition(sizeBytes = 100L, modifiedAtEpochMs = 200L)

    @Test
    fun unchangedSourcePasses() {
        assertThat(ReviewedSources.failure("/a", reviewed, exists = true, current = SourcePrecondition(100L, 200L))).isNull()
    }

    @Test
    fun editedAfterReviewIsRefused() {
        assertThat(ReviewedSources.failure("/a", reviewed, exists = true, current = SourcePrecondition(100L, 999L)))
            .contains("changed after you reviewed")
        assertThat(ReviewedSources.failure("/a", reviewed, exists = true, current = SourcePrecondition(101L, 200L)))
            .contains("changed after you reviewed")
    }

    @Test
    fun deletedAfterReviewIsRefused() {
        assertThat(ReviewedSources.failure("/a", reviewed, exists = false, current = null))
            .contains("disappeared after you reviewed")
    }

    @Test
    fun replacedByDirectoryIsRefused() {
        assertThat(ReviewedSources.failure("/a", reviewed, exists = true, current = null))
            .contains("type changed after you reviewed")
    }

    @Test
    fun sourceOfCoversEveryMutatingOperationOnly() {
        val src = FileRef.Direct("/Download/a.txt")
        val dst = FileRef.Direct("/Documents/a.txt")
        assertThat(ReviewedSources.sourceOf(PlannedOperation.Move(src, dst, "m"))).isEqualTo(src)
        assertThat(ReviewedSources.sourceOf(PlannedOperation.Copy(src, dst, "c"))).isEqualTo(src)
        assertThat(ReviewedSources.sourceOf(PlannedOperation.Rename(src, "b.txt", "r"))).isEqualTo(src)
        assertThat(ReviewedSources.sourceOf(PlannedOperation.Trash(src, "t"))).isEqualTo(src)
        assertThat(ReviewedSources.sourceOf(PlannedOperation.CreateDirectory(FileRef.Direct("/Documents"), "x", "d"))).isNull()
        assertThat(ReviewedSources.sourceOf(PlannedOperation.WriteTextFile(FileRef.Direct("/Documents"), "n.txt", "hi", "w"))).isNull()
    }
}
