package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

/** Host JVM engine/adaptor timing only: excludes Android scanning, IO, OCR and rendering. */
class ReviewScaleBenchmarkTest {
    @Test(timeout = 180_000) fun coldAndWarmReviewsWithManyCompetingHomes() {
        val root = "/storage/emulated/0"
        val homes = List(200) { n -> ProjectHomeCandidate("Project $n", "$root/Documents/Project $n",
            hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = true) }
        val dirs = homes.flatMap { listOf(it.path, "${it.path}/Notes") }.toSet()
        for (count in listOf(1_000, 4_000, 16_000)) {
            val artifacts = List(count) { n ->
                val project = n % 200
                val name = "Project-$project-notes-$n.txt"
                FilingArtifact("$root/Download/Uncertain/$name", name, "txt", 12, modifiedAt = n.toLong(), parentRef = "$root/Download/Uncertain")
            }
            for (pass in listOf("cold", "warm")) {
                val started = System.nanoTime()
                val result = InboxFilingEngine.resolve(artifacts, homes, emptyList(), emptyList(), emptyList(), root)
                val inferredAt = System.nanoTime()
                val plan = InboxFilingPlanAdapter.build(result, FileRef.Direct(root), dirs)
                val plannedAt = System.nanoTime()
                assertThat(result.decisions).hasSize(count)
                assertThat(result.decisions.all { it.confidence == FilingConfidence.STRONG }).isTrue()
                result.decisions.forEachIndexed { n, decision -> assertThat(decision.projectHome?.path).isEqualTo(homes[n % 200].path) }
                assertThat(plan.defaultSelectedSourceRefs).hasSize(count)
                println("REVIEW_BENCH count=$count homes=200 pass=$pass inferMs=${(inferredAt - started) / 1_000_000} planMs=${(plannedAt - inferredAt) / 1_000_000}")
            }
        }
    }
}
