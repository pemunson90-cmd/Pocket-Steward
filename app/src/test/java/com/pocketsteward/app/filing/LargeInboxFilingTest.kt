package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import org.junit.Test

class LargeInboxFilingTest {
    @Test(timeout = 20_000) fun sixteenThousandMixedFilesRetainConfidenceAndProjectRelationships() {
        val home = ProjectHomeCandidate("NSTL", "/storage/emulated/0/Documents/NSTL", aliases = listOf("NSTL"), hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = true)
        val items = listOf(artifact("NSTL-v1.2.3.apk", "apk")) +
            (1..8_000).map { artifact("NSTL-notes-$it.txt", "txt") } +
            (1..7_999).map { artifact("image-$it.png", "png") }
        val started = System.nanoTime()
        val result = InboxFilingEngine.resolve(items, listOf(home), emptyList(), emptyList(), emptyList(), "/storage/emulated/0")
        println("16,000-file filing engine: ${(System.nanoTime() - started) / 1_000_000} ms")
        assertThat(result.decisions).hasSize(16_000)
        assertThat(result.decisions.take(8_001).all { it.confidence == FilingConfidence.STRONG }).isTrue()
        assertThat(result.decisions.drop(8_001).all { it.confidence == FilingConfidence.PROBABLE }).isTrue()
        assertThat(result.decisions.map { it.projectHome?.path }.distinct()).containsExactly(home.path)
        assertThat(result.decisions[1].destinationDirectory).isEqualTo("${home.path}/Notes")
        assertThat(result.decisions.last().destinationDirectory).isEqualTo("${home.path}/Images")
        val plan = InboxFilingPlanAdapter.build(result, com.pocketsteward.app.storage.FileRef.Direct("/storage/emulated/0"), setOf(home.path, "${home.path}/Notes", "${home.path}/Images"))
        assertThat(plan.defaultSelectedSourceRefs).hasSize(8_001)
    }
    @Test fun competingCohortsDoNotAttachAnUnrelatedImage() {
        val items = listOf(artifact("NSTL-v1.2.3.apk", "apk"), artifact("Lilith-v2.3.4.apk", "apk"), artifact("image-1.png", "png"))
        val result = InboxFilingEngine.resolve(items, emptyList(), emptyList(), emptyList(), emptyList(), "/storage/emulated/0")
        assertThat(result.decisions.last().confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }
    private fun artifact(name: String, ext: String) = FilingArtifact("/storage/emulated/0/Download/Uncertain/$name", name, ext, 10, modifiedAt = 1_000, parentRef = "/storage/emulated/0/Download/Uncertain")
}
