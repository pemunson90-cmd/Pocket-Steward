package com.pocketsteward.app.versions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class VersionChainsTest {
    private fun file(path: String, modified: Long, dir: Boolean = false): FileRecord {
        val name = path.substringAfterLast('/')
        return FileRecord(
            stableRef = path,
            displayName = name,
            extension = if (dir) "" else name.substringAfterLast('.', ""),
            mimeType = null,
            absolutePathOrUri = path,
            parentRef = path.substringBeforeLast('/'),
            sizeBytes = 100,
            createdAt = null,
            modifiedAt = modified,
            lastScannedAt = 0,
            isDirectory = dir,
            isHidden = false,
        )
    }

    @Test
    fun stripsStackedVersionMarkers() {
        val same = listOf(
            "Resume", "Resume (1)", "Resume(2)", "Resume - Copy", "Copy of Resume", "Resume copy 2",
            "Resume_v2", "resume v1.3", "Resume rev 4", "Resume FINAL", "Resume_final_final",
            "Resume_v2 final (1)", "Resume-draft2", "Resume [3]",
        )
        same.forEach { assertWithMessage(it).that(VersionChains.familyKey(it)).isEqualTo("resume") }
    }

    @Test
    fun keepsDatesNumbersAndWordsThatMerelyEndInAMarker() {
        assertThat(VersionChains.familyKey("Statement 2026-05"))
            .isNotEqualTo(VersionChains.familyKey("Statement 2026-06"))
        assertThat(VersionChains.familyKey("Invoice 1043")).isNotEqualTo(VersionChains.familyKey("Invoice 1044"))
        assertThat(VersionChains.familyKey("Threshold")).isEqualTo("threshold")
        assertThat(VersionChains.familyKey("Renew")).isEqualTo("renew")
        assertThat(VersionChains.familyKey("Semifinal")).isEqualTo("semifinal")
    }

    @Test
    fun newestSurvivesAndOlderVersionsAreProposedForMoving() {
        val records = listOf(
            file("/sd/Docs/Resume.pdf", 100),
            file("/sd/Docs/Resume (1).pdf", 300),
            file("/sd/Docs/Resume_v2.pdf", 200),
            file("/sd/Docs/Unrelated.pdf", 50),
        )
        val chain = VersionChains.detect(records).single()
        assertThat(chain.latest.displayName).isEqualTo("Resume (1).pdf")
        assertThat(chain.older.map { it.displayName }).containsExactly("Resume_v2.pdf", "Resume.pdf").inOrder()

        val ops = VersionChains.planOperations(listOf(chain), records)
        val create = ops.first() as PlannedOperation.CreateDirectory
        assertThat(create.parent).isEqualTo(FileRef.Direct("/sd/Docs"))
        assertThat(create.name).isEqualTo(VersionChains.OLDER_VERSIONS_FOLDER)
        val moves = ops.drop(1).map { it as PlannedOperation.Move }
        assertThat(moves.map { it.destination }).containsExactly(
            FileRef.Direct("/sd/Docs/Older versions/Resume_v2.pdf"),
            FileRef.Direct("/sd/Docs/Older versions/Resume.pdf"),
        ).inOrder()
        assertWithMessage("nothing is trashed or renamed")
            .that(ops.none { it is PlannedOperation.Trash || it is PlannedOperation.Rename }).isTrue()
    }

    @Test
    fun differentFoldersOrExtensionsAreNotOneChain() {
        val records = listOf(
            file("/sd/A/Report.docx", 1),
            file("/sd/B/Report (1).docx", 2),
            file("/sd/A/Report (1).pdf", 3),
        )
        assertThat(VersionChains.detect(records)).isEmpty()
    }

    @Test
    fun reusesAnExistingOlderVersionsFolderAndIgnoresWhatIsAlreadyInIt() {
        val records = listOf(
            file("/sd/Docs/Older versions", 0, dir = true),
            file("/sd/Docs/Older versions/Plan (1).txt", 1),
            file("/sd/Docs/Plan.txt", 5),
            file("/sd/Docs/Plan_v2.txt", 4),
        )
        val chains = VersionChains.detect(records)
        assertThat(chains.single().members.map { it.displayName }).containsExactly("Plan.txt", "Plan_v2.txt").inOrder()
        val ops = VersionChains.planOperations(chains, records)
        assertThat(ops.filterIsInstance<PlannedOperation.CreateDirectory>()).isEmpty()
        assertThat((ops.single() as PlannedOperation.Move).destination)
            .isEqualTo(FileRef.Direct("/sd/Docs/Older versions/Plan_v2.txt"))
    }

    @Test
    fun protectedFoldersAreLeftAlone() {
        val records = listOf(
            file("/sd/Keep/$DO_NOT_SORT_MARKER", 0),
            file("/sd/Keep/Notes.md", 1),
            file("/sd/Keep/Notes (1).md", 2),
        )
        assertThat(VersionChains.detect(records)).isEmpty()
    }

    @Test
    fun equalTimesFallBackToTheHigherVersionNumber() {
        val records = listOf(file("/sd/D/Spec v2.md", 7), file("/sd/D/Spec v10.md", 7))
        assertThat(VersionChains.detect(records).single().latest.displayName).isEqualTo("Spec v10.md")
    }
}
