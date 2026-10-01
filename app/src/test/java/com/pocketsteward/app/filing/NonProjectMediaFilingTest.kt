package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NonProjectMediaFilingTest {
    @Test fun albumTagsSuggestOneSafeHomeWithoutAutomaticSelectionConfidence() {
        val decision = requireNotNull(NonProjectMediaFiling.propose(file("track.mp3").copy(mediaArtist = "A/B", mediaAlbum = "Collection"), "/storage"))
        assertThat(decision.destinationDirectory).isEqualTo("/storage/Music/A_B/Collection")
        assertThat(decision.confidence).isEqualTo(FilingConfidence.PROBABLE)
    }
    @Test fun cameraDateUsesEventFolderAndInvalidDateHasNoSuggestion() {
        assertThat(NonProjectMediaFiling.propose(file("photo.jpg").copy(captureDate = "2026-09-30"), "/storage")?.destinationDirectory).isEqualTo("/storage/Images/Events/2026-09-30")
        assertThat(NonProjectMediaFiling.propose(file("photo.jpg").copy(captureDate = "2026-02-31"), "/storage")).isNull()
    }
    @Test fun conflictingTopicsDoNotInventCategory() {
        assertThat(NonProjectMediaFiling.propose(file("photo.jpg").copy(imageLabels = listOf("landscape", "portrait")), "/storage")).isNull()
        assertThat(NonProjectMediaFiling.propose(file("photo.jpg").copy(imageLabels = listOf("mountain", "nature")), "/storage")?.destinationDirectory).isEqualTo("/storage/Images/Landscape")
    }
    @Test fun missingTagsAndFoldersRemainUnresolved() {
        assertThat(NonProjectMediaFiling.propose(file("track.mp3"), "/storage")).isNull()
        assertThat(NonProjectMediaFiling.propose(file("photos.jpg").copy(isDirectory = true, captureDate = "2026-09-30"), "/storage")).isNull()
    }
    @Test fun projectCohortKeepsLabeledImageWithItsProject() {
        val home = ProjectHomeCandidate("NSTL", "/storage/Documents/NSTL", hierarchy = com.pocketsteward.app.saved.ProjectHierarchyStrategy.PROJECT_ROLES)
        val result = InboxFilingEngine.resolve(listOf(file("NSTL-notes.txt"), file("photo.jpg").copy(imageLabels = listOf("mountain"))), listOf(home), emptyList(), emptyList(), emptyList(), "/storage")
        assertThat(result.decisions.last().destinationDirectory).isEqualTo("/storage/Documents/NSTL/Images")
        assertThat(result.decisions.last().confidence).isEqualTo(FilingConfidence.PROBABLE)
    }
    private fun file(name: String) = FilingArtifact("/inbox/$name", name, name.substringAfterLast('.'), 10, modifiedAt = 1, parentRef = "/inbox")
}
