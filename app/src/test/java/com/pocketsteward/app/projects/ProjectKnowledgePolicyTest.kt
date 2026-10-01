package com.pocketsteward.app.projects

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.IndexedProjectLayout
import org.junit.Test

class ProjectKnowledgePolicyTest {
    private val root = "/storage/emulated/0"
    private fun layout(path: String, roles: String = "Notes\nImages") =
        IndexedProjectLayout(path, path.substringAfterLast('/'), false, roles)

    @Test fun findsAnExistingProjectOutsideImmediateDocuments() {
        val result = ProjectKnowledgePolicy.candidate(root, listOf("$root/Download"), layout("$root/Projects/Lilith"))
        assertThat(result?.path).isEqualTo("$root/Projects/Lilith")
        assertThat(result?.persisted).isFalse()
    }

    @Test fun preservesTheObservedSpellingOfRoleFolders() {
        val result = ProjectKnowledgePolicy.candidate(root, emptyList(), layout("$root/Projects/Lilith", "notes\nIMAGES"))
        assertThat(result?.roleFolders).containsExactly("Notes", "notes", "Images", "IMAGES")
    }

    @Test fun caseCompetingRoleFoldersDoNotChooseAnArbitraryConvention() {
        assertThat(ProjectKnowledgePolicy.candidate(root, emptyList(), layout("$root/Projects/Lilith", "Notes\nnotes"))).isNull()
    }

    @Test fun anInboxProjectOrCheckpointCannotBecomeADurableDestination() {
        for (path in listOf("$root/Download/Lilith", "$root/Downloads/Uncertain/Lilith", "$root/Landing/Lilith")) {
            assertThat(ProjectKnowledgePolicy.candidate(root, listOf("$root/Landing"), layout(path))).isNull()
        }
        assertThat(ProjectKnowledgePolicy.candidate(root, listOf("$root/Landing"), layout("$root/LandingElsewhere/Lilith"))).isNotNull()
    }

    @Test fun hiddenPrivateAndGenericFoldersAreNotProjectHomes() {
        for (path in listOf("$root/Android/data/Lilith", "$root/.private/Lilith", "$root/Documents", "$root/Projects/Notes")) {
            assertThat(ProjectKnowledgePolicy.candidate(root, emptyList(), layout(path))).isNull()
        }
    }

    @Test fun rootBoundariesAndDotTraversalFailClosed() {
        for (path in listOf("/storage/emulated/01/Lilith", "$root/../Lilith", "content://provider/Lilith", "$root/Lilith//Lilith")) {
            assertThat(ProjectKnowledgePolicy.candidate(root, emptyList(), layout(path))).isNull()
        }
    }

    @Test fun sixteenThousandFoldersDoNotChangeProjectIdentityOrRoleSpelling() {
        val candidates = (1..16_000).map { layout("$root/Projects/Project $it", "notes") }
        val homes = candidates.mapNotNull { ProjectKnowledgePolicy.candidate(root, emptyList(), it) }
        assertThat(homes.size).isEqualTo(16_000)
        assertThat(homes.map { it.path }.distinct().size).isEqualTo(16_000)
        assertThat(homes.all { it.roleFolders["Notes"] == "notes" }).isTrue()
    }
}
