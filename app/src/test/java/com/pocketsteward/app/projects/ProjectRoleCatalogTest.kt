package com.pocketsteward.app.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectRoleCatalogTest {
    @Test fun recognizesCustomAndNestedRolePathsWithoutChangingObservedRootSpelling() {
        val catalog = ProjectRoleCatalog(listOf(mapOf("Manuscript" to "Writing/manuscript", "Images" to "Art")))
        assertThat(catalog.rootNames).containsAtLeast("writing", "art", "notes")
        assertThat(catalog.observedRoles("WRITING\nart\nnotes")).containsExactly("Manuscript", "WRITING/manuscript", "Images", "art", "Notes", "notes")
    }
    @Test fun competingStandardAndCustomPathsDoNotChooseAnArbitraryRoleConvention() {
        val catalog = ProjectRoleCatalog(listOf(mapOf("Manuscript" to "Writing/manuscript")))
        assertThat(catalog.observedRoles("Manuscript\nWriting")).isNull()
    }
    @Test fun unsafeCustomPathsCannotBecomeIndexedDiscoveryPatterns() {
        assertThat(runCatching { ProjectRoleCatalog(listOf(mapOf("Notes" to "../private"))) }.isFailure).isTrue()
    }
}
