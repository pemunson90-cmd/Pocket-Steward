package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import org.junit.Test

class InboxFilingPlanAdapterTest {
    @Test
    fun newDirectProjectBuildsHomeThenVersionThenMove() {
        val decision = decision(
            home = ProjectHomeCandidate(
                name = "Lilith Companion App",
                path = "/storage/emulated/0/Lilith Companion App",
                hierarchy = ProjectHierarchyStrategy.VERSIONED,
            ),
            release = "0.3.105",
        )
        val plan = InboxFilingPlanAdapter.build(
            result = InboxFilingResult(listOf(decision)),
            storageRoot = FileRef.Direct("/storage/emulated/0"),
            existingDirectories = setOf("/storage/emulated/0"),
        )

        assertThat(plan.operations).hasSize(3)
        assertThat(plan.operations[0]).isInstanceOf(PlannedOperation.CreateDirectory::class.java)
        assertThat(plan.operations[1]).isInstanceOf(PlannedOperation.CreateDirectory::class.java)
        val move = plan.operations[2] as PlannedOperation.Move
        assertThat(move.destination.rawValue())
            .isEqualTo("/storage/emulated/0/Lilith Companion App/0.3.105/LilithCompanion-0.3.105.apk")
        assertThat(plan.defaultSelectedSourceRefs).containsExactly("/storage/emulated/0/Download/LilithCompanion-0.3.105.apk")
    }

    @Test
    fun existingDirectProjectAuthorizesHomeNotWholeStorageRoot() {
        val home = ProjectHomeCandidate(
            name = "Lilith Companion App",
            path = "/storage/emulated/0/Lilith Companion App",
            persisted = true,
        )
        val plan = InboxFilingPlanAdapter.build(
            result = InboxFilingResult(listOf(decision(home, "0.3.105"))),
            storageRoot = FileRef.Direct("/storage/emulated/0"),
            existingDirectories = setOf(home.path),
        )

        assertThat(plan.authorizedDestinationRoots.map { it.absolutePath })
            .containsExactly(home.path)
    }

    @Test
    fun safPlanUsesTypedChildrenRatherThanInventingUris() {
        val root = FileRef.Saf("content://provider/tree/root")
        val home = ProjectHomeCandidate(
            name = "Lilith Companion App",
            path = "content://provider/tree/root/Lilith Companion App",
            hierarchy = ProjectHierarchyStrategy.VERSIONED,
        )
        val plan = InboxFilingSafPlanAdapter.build(
            result = InboxFilingResult(listOf(decision(home, "0.3.105"))),
            scopeRoot = root,
            existingHomes = emptyMap(),
            scopeLabel = "Granted inbox",
        )

        val homeCreate = plan.operations[0] as PlannedOperation.CreateDirectory
        val releaseCreate = plan.operations[1] as PlannedOperation.CreateDirectory
        val move = plan.operations[2] as PlannedOperation.Move
        assertThat(homeCreate.parent).isEqualTo(root)
        assertThat(releaseCreate.parent).isInstanceOf(FileRef.Child::class.java)
        assertThat(move.destination).isInstanceOf(FileRef.Child::class.java)
        assertThat(plan.presentation.groups.single().editableDirectDestination).isFalse()
    }

    private fun decision(home: ProjectHomeCandidate, release: String) = FilingDecision(
        artifact = FilingArtifact(
            stableRef = "/storage/emulated/0/Download/LilithCompanion-0.3.105.apk",
            displayName = "LilithCompanion-0.3.105.apk",
            extension = "apk",
            sizeBytes = 10,
            createdAt = 1,
            modifiedAt = 1,
            parentRef = "/storage/emulated/0/Download",
        ),
        projectName = home.name,
        projectHome = home,
        release = release,
        destinationDirectory = home.path.trimEnd('/') + "/" + release,
        confidence = FilingConfidence.STRONG,
        evidence = listOf(FilingEvidence(FilingEvidenceKind.FILENAME, "project", 100)),
        createsProjectHome = !home.persisted,
    )
}