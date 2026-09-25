package com.pocketsteward.app.inbox

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchy
import com.pocketsteward.app.saved.ProjectHome
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class InboxPlanAdapterTest {
    private val storage = "/storage/emulated/0"
    private val downloads = "$storage/Download"
    private val home = ProjectHome(
        id = "lilith",
        name = "Lilith Companion App",
        path = "$storage/Lilith Companion App",
        aliases = listOf("LilithCompanion"),
        hierarchy = ProjectHierarchy.VERSIONED,
    )

    @Test
    fun existingHomeCreatesOnlyMissingVersionFolderThenMovesArtifact() {
        val decision = decision("LilithCompanion-0.3.105.apk", "0.3.105", FilingConfidence.STRONG)
        val result = InboxPlanAdapter.build(
            analysis = InboxFilingAnalysis(listOf(decision), emptyList()),
            storageRootPath = storage,
            existingDirectories = setOf(storage, downloads, home.path),
        )

        val creates = result.operations.filterIsInstance<PlannedOperation.CreateDirectory>()
        assertThat(creates).hasSize(1)
        assertThat((creates.single().parent as FileRef.Direct).absolutePath).isEqualTo(home.path)
        assertThat(creates.single().name).isEqualTo("0.3.105")
        val move = result.operations.filterIsInstance<PlannedOperation.Move>().single()
        assertThat((move.destination as FileRef.Direct).absolutePath)
            .isEqualTo("${home.path}/0.3.105/LilithCompanion-0.3.105.apk")
        assertThat(result.strongSourceRefs).containsExactly(decision.artifact.stableRef)
        assertThat(result.authorizedDestinationRoots.map { it.absolutePath }).contains(home.path)
    }

    @Test
    fun missingProjectHomeCreatesHomeAndVersionInOrder() {
        val decision = decision("LilithCompanion-0.3.105.apk", "0.3.105", FilingConfidence.STRONG)
        val result = InboxPlanAdapter.build(
            analysis = InboxFilingAnalysis(listOf(decision), emptyList()),
            storageRootPath = storage,
            existingDirectories = setOf(storage, downloads),
        )

        val creates = result.operations.filterIsInstance<PlannedOperation.CreateDirectory>()
        assertThat(creates.map { it.name }).containsExactly("Lilith Companion App", "0.3.105").inOrder()
        assertThat(result.authorizedDestinationRoots.map { it.absolutePath }).contains(storage)
    }

    @Test
    fun probableMatchIsPlannedButNotDefaultSelected() {
        val decision = decision("lilith-back-turnaround.png", null, FilingConfidence.PROBABLE)
        val result = InboxPlanAdapter.build(
            analysis = InboxFilingAnalysis(listOf(decision), emptyList()),
            storageRootPath = storage,
            existingDirectories = setOf(storage, downloads, home.path),
        )

        assertThat(result.operations.filterIsInstance<PlannedOperation.Move>()).hasSize(1)
        assertThat(result.strongSourceRefs).isEmpty()
        assertThat(result.filingHints.values.single().confidence).isEqualTo(FilingConfidence.PROBABLE)
    }

    @Test
    fun protectedProjectHomeIsNotPlanned() {
        val decision = decision("LilithCompanion-0.3.105.apk", "0.3.105", FilingConfidence.STRONG)
        val result = InboxPlanAdapter.build(
            analysis = InboxFilingAnalysis(listOf(decision), emptyList()),
            storageRootPath = storage,
            existingDirectories = setOf(storage, downloads, home.path),
            protectedDirectories = setOf(home.path),
        )

        assertThat(result.operations).isEmpty()
        assertThat(result.notes.joinToString(" ")).contains("protected")
    }

    @Test
    fun unsafeReleaseCannotEscapeProjectHome() {
        val decision = decision("LilithCompanion.apk", "../Other", FilingConfidence.STRONG)
        val result = InboxPlanAdapter.build(
            analysis = InboxFilingAnalysis(listOf(decision), emptyList()),
            storageRootPath = storage,
            existingDirectories = setOf(storage, downloads, home.path),
        )

        val move = result.operations.filterIsInstance<PlannedOperation.Move>().single()
        assertThat((move.destination as FileRef.Direct).absolutePath)
            .isEqualTo("${home.path}/LilithCompanion.apk")
    }

    @Test
    fun homeWhoseParentDoesNotExistIsRejectedForEachDecision() {
        val missingHome = home.copy(path = "$storage/Projects/Lilith Companion App")
        val first = decision("LilithCompanion-0.3.105.apk", "0.3.105", FilingConfidence.STRONG, missingHome)
        val second = decision("LilithCompanion-0.3.105-SOURCE.zip", "0.3.105", FilingConfidence.STRONG, missingHome)
        val result = InboxPlanAdapter.build(
            analysis = InboxFilingAnalysis(listOf(first, second), emptyList()),
            storageRootPath = storage,
            existingDirectories = setOf(storage, downloads),
        )

        assertThat(result.operations).isEmpty()
        assertThat(result.notes.count { it.contains("parent of proposed project home") }).isEqualTo(2)
    }

    private fun decision(
        name: String,
        release: String?,
        confidence: FilingConfidence,
        projectHome: ProjectHome = home,
    ) = FilingDecision(
        artifact = ArtifactSignals(
            stableRef = "$downloads/$name",
            displayName = name,
            extension = name.substringAfterLast('.', ""),
            sizeBytes = 4096,
            modifiedAt = 1000L,
        ),
        projectHome = projectHome,
        release = release,
        confidence = confidence,
        evidence = listOf(FilingEvidence(FilingEvidenceType.FILENAME, "test evidence", 100)),
    )
}
