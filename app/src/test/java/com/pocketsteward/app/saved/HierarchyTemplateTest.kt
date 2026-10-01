package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.filing.InboxFilingEngine
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.filing.ProjectHomeCandidate
import org.junit.Test

class HierarchyTemplateTest {
    @Test fun explicitTemplateIsRoundTrippableAndSupportsRootAndNestedRoles() {
        val template = HierarchyTemplate.parse("Manuscript=Writing/Manuscript\nNotes=Research\nImages=")
        assertThat(HierarchyTemplate.parse(template.encode())).isEqualTo(template)
        val home = ProjectHomeCandidate("NSTL", "/Documents/NSTL", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, roleFolders = template.roleFolders)
        assertThat(InboxFilingEngine.destinationFor(home, null, artifact("NSTL-notes.txt", "txt"))).isEqualTo("/Documents/NSTL/Research")
        assertThat(InboxFilingEngine.destinationFor(home, null, artifact("NSTL-cover.png", "png"))).isEqualTo("/Documents/NSTL")
        val saved = ProjectHome(name = "NSTL", path = home.path, hierarchy = home.hierarchy, roleFolders = home.roleFolders)
        assertThat(OrganizationPreferenceCodec.decodeProjectHomes(OrganizationPreferenceCodec.encodeProjectHomes(listOf(saved)))).containsExactly(saved)
    }
    @Test(expected = IllegalArgumentException::class) fun traversalRejected() { HierarchyTemplate.parse("Notes=../elsewhere") }
    @Test(expected = IllegalArgumentException::class) fun absoluteFolderRejected() { HierarchyTemplate.parse("Notes=/Documents") }
    @Test(expected = IllegalArgumentException::class) fun duplicateRoleRejected() { HierarchyTemplate.parse("Notes=A\nNotes=B") }
    @Test fun categoryHierarchyHasDeterministicShallowFolders() {
        val home = ProjectHomeCandidate("Lilith", "/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.CATEGORY)
        assertThat(InboxFilingEngine.destinationFor(home, null, artifact("cover.png", "png"))).isEqualTo("/Documents/Lilith/Images")
    }
    private fun artifact(name: String, ext: String) = FilingArtifact("/inbox/$name", name, ext, 10, modifiedAt = 1, parentRef = "/inbox")
}
