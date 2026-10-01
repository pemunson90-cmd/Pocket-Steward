package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectHomeRankingTest {
    @Test fun exactAliasOutranksPartialNameAndDuplicateNamesKeepDistinctPaths() {
        val a = ProjectHomeCandidate("NSTL", "/Documents/NSTL", aliases = listOf("Story"), persisted = true)
        val b = ProjectHomeCandidate("Story Drafts", "/Documents/Drafts")
        val c = ProjectHomeCandidate("NSTL", "/Archives/NSTL", aliases = listOf("Story"))
        assertThat(ProjectHomeRanking.rank(listOf(b, c, a), "story")).containsExactly(a, c, b).inOrder()
        assertThat(ProjectHomeRanking.rank(listOf(a, c), "NSTL")).hasSize(2)
        assertThat(ProjectHomeRanking.rank(listOf(a, b), "missing")).isEmpty()
    }
}
