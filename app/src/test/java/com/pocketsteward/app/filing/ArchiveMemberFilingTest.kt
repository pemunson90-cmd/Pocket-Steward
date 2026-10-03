package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.metadata.ArchiveInspector
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import org.junit.Test
import java.io.File

/**
 * Member names observed in real RAR/7z fixtures feed the existing filing rules.
 * The archive file names carry no project or version hint, so any inference here
 * comes from the inspected member paths alone.
 */
class ArchiveMemberFilingTest {
    private val root = "/storage/emulated/0"
    private val nstl = ProjectHomeCandidate("NSTL", "$root/Documents/NSTL", hierarchy = ProjectHierarchyStrategy.VERSIONED, persisted = true)

    @Test fun observedRarAndSevenZipMembersIdentifyProjectAndRelease() {
        for (fixture in listOf("project-release-rar4.rar", "project-release-rar5.rar", "project-release.7z")) {
            val artifact = artifactFor(fixture)
            assertThat(artifact.archiveSample).contains("NSTL/NSTL-1.4.0-dev18/release/NSTL-1.4.0-dev18.apk")
            val decision = InboxFilingEngine.resolve(listOf(artifact), listOf(nstl), emptyList(), emptyList(), emptyList(), root).decisions.single()
            assertThat(decision.projectName).isEqualTo("NSTL")
            assertThat(decision.release).isEqualTo("1.4.0-dev18")
            assertThat(decision.evidence.map { it.kind }).contains(FilingEvidenceKind.ARCHIVE_ENTRY)
            // Archive members alone are proposed for review, not pre-selected (existing weights).
            assertThat(decision.confidence).isEqualTo(FilingConfidence.PROBABLE)
            assertThat(decision.destinationDirectory).isEqualTo("$root/Documents/NSTL/1.4.0-dev18")
            assertThat(InboxFilingEngine.releaseOf(artifact)).isEqualTo("1.4.0-dev18")
        }
    }

    @Test fun withoutObservedMembersTheSameArchiveIsNotAssigned() {
        for (fixture in listOf("project-release-rar5.rar", "encrypted-headers-rar5.rar", "encrypted-headers.7z")) {
            val artifact = if (fixture.startsWith("project")) artifactFor(fixture).copy(archiveSample = emptyList()) else artifactFor(fixture)
            assertThat(artifact.archiveSample).isEmpty()
            val decision = InboxFilingEngine.resolve(listOf(artifact), listOf(nstl), emptyList(), emptyList(), emptyList(), root).decisions.single()
            assertThat(decision.projectName).isNull()
            assertThat(InboxFilingEngine.releaseOf(artifact)).isNull()
        }
    }

    @Test fun membersMatchingTwoHomesStayAmbiguous() {
        val release = ProjectHomeCandidate("Release", "$root/Documents/Release", hierarchy = ProjectHierarchyStrategy.VERSIONED, persisted = true)
        for (fixture in listOf("project-release-rar5.rar", "project-release.7z")) {
            val result = InboxFilingEngine.resolve(listOf(artifactFor(fixture)), listOf(nstl, release), emptyList(), emptyList(), emptyList(), root)
            assertThat(result.proposed).isEmpty()
            assertThat(result.unresolved.single().evidenceSummary).contains("matches both")
        }
    }

    private fun artifactFor(fixture: String): FilingArtifact {
        val file = File(requireNotNull(javaClass.classLoader!!.getResource("archive-fixtures/$fixture")).toURI())
        val extension = fixture.substringAfterLast('.')
        val inspection = ArchiveInspector.file(file, extension)
        return FilingArtifact("$root/Download/bundle.$extension", "bundle.$extension", extension, file.length(), modifiedAt = 1,
            parentRef = "$root/Download", archiveSample = inspection.names)
    }
}
