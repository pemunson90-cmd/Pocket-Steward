package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import org.junit.Test

class InboxFilingPlanAdapterTest {
    @Test
    fun folderBecomesNewProjectHomeWithoutDuplicateNesting() {
        val artifact = FilingArtifact("/storage/emulated/0/Download/NSTL", "NSTL", "", 100, modifiedAt = 1, parentRef = "/storage/emulated/0/Download", isDirectory = true)
        val home = ProjectHomeCandidate("NSTL", "/storage/emulated/0/Documents/NSTL", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val decision = FilingDecision(artifact, "NSTL", home, null, "/storage/emulated/0/Documents", FilingConfidence.STRONG, emptyList(), createsProjectHome = true)
        val plan = InboxFilingPlanAdapter.build(InboxFilingResult(listOf(decision)), FileRef.Direct("/storage/emulated/0"), setOf("/storage/emulated/0/Documents"))
        assertThat(plan.operations).hasSize(1)
        assertThat((plan.operations.single() as PlannedOperation.Move).destination.rawValue()).isEqualTo(home.path)
        assertThat(plan.presentation.groups.single().items.single().isDirectory).isTrue()
    }

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
    fun newDocumentProjectCreatesEachFolderBeforeMovingFile() {
        val home = ProjectHomeCandidate(
            name = "NSTL",
            path = "/storage/emulated/0/Documents/NSTL",
            hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES,
        )
        val artifact = FilingArtifact(
            stableRef = "/storage/emulated/0/Download/NSTL-draft.txt",
            displayName = "NSTL-draft.txt",
            extension = "txt",
            sizeBytes = 10,
            modifiedAt = 1,
            parentRef = "/storage/emulated/0/Download",
        )
        val decision = FilingDecision(
            artifact = artifact,
            projectName = "NSTL",
            projectHome = home,
            release = null,
            destinationDirectory = "${home.path}/Drafts",
            confidence = FilingConfidence.STRONG,
            evidence = emptyList(),
        )
        val plan = InboxFilingPlanAdapter.build(
            result = InboxFilingResult(listOf(decision)),
            storageRoot = FileRef.Direct("/storage/emulated/0"),
            existingDirectories = setOf("/storage/emulated/0/Documents"),
        )
        val directories = plan.operations.filterIsInstance<PlannedOperation.CreateDirectory>()
        assertThat(directories.map { it.name }).containsExactly("NSTL", "Drafts").inOrder()
        assertThat(plan.operations.last()).isInstanceOf(PlannedOperation.Move::class.java)
        assertThat(plan.authorizedDestinationRoots.map { it.absolutePath })
            .containsExactly("/storage/emulated/0")
    }

    @Test
    fun unresolvedLooseFileMovesIntoDownloadsUncertainCheckpoint() {
        val artifact = FilingArtifact(
            stableRef = "/storage/emulated/0/Download/mystery.txt",
            displayName = "mystery.txt",
            extension = "txt",
            sizeBytes = 10,
            modifiedAt = 1,
            parentRef = "/storage/emulated/0/Download",
        )
        val unresolved = FilingDecision(
            artifact = artifact,
            projectName = null,
            projectHome = null,
            release = null,
            destinationDirectory = null,
            confidence = FilingConfidence.UNRESOLVED,
            evidence = emptyList(),
        )
        val plan = InboxFilingPlanAdapter.build(
            result = InboxFilingResult(listOf(unresolved)),
            storageRoot = FileRef.Direct("/storage/emulated/0"),
            existingDirectories = setOf("/storage/emulated/0/Download"),
        )
        assertThat((plan.operations[0] as PlannedOperation.CreateDirectory).name).isEqualTo("Uncertain")
        assertThat((plan.operations[1] as PlannedOperation.Move).destination.rawValue())
            .isEqualTo("/storage/emulated/0/Download/Uncertain/mystery.txt")
        assertThat(plan.defaultSelectedSourceRefs).containsExactly(artifact.stableRef)
        assertThat(plan.presentation.checkpointCount).isEqualTo(1)
        assertThat(plan.presentation.checkpointGroups.single().isUncertainCheckpoint).isTrue()
    }

    @Test
    fun existingUncertainCheckpointIsReused() {
        val unresolved = FilingDecision(
            artifact = FilingArtifact(
                stableRef = "/storage/emulated/0/Download/mystery.txt",
                displayName = "mystery.txt",
                extension = "txt",
                sizeBytes = 10,
                modifiedAt = 1,
                parentRef = "/storage/emulated/0/Download",
            ),
            projectName = null,
            projectHome = null,
            release = null,
            destinationDirectory = null,
            confidence = FilingConfidence.UNRESOLVED,
            evidence = emptyList(),
        )
        val plan = InboxFilingPlanAdapter.build(
            result = InboxFilingResult(listOf(unresolved)),
            storageRoot = FileRef.Direct("/storage/emulated/0"),
            existingDirectories = setOf(
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Download/Uncertain",
            ),
        )
        assertThat(plan.operations).hasSize(1)
        assertThat(plan.operations.single()).isInstanceOf(PlannedOperation.Move::class.java)
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

    @Test
    fun safUnresolvedFileUsesTypedUncertainChild() {
        val root = FileRef.Saf("content://provider/tree/root")
        val unresolved = FilingDecision(
            artifact = FilingArtifact(
                stableRef = "content://provider/tree/root/document/mystery.txt",
                displayName = "mystery.txt",
                extension = "txt",
                sizeBytes = 10,
                modifiedAt = 1,
                parentRef = root.rawValue(),
            ),
            projectName = null,
            projectHome = null,
            release = null,
            destinationDirectory = null,
            confidence = FilingConfidence.UNRESOLVED,
            evidence = emptyList(),
        )
        val plan = InboxFilingSafPlanAdapter.build(
            result = InboxFilingResult(listOf(unresolved)),
            scopeRoot = root,
            existingHomes = emptyMap(),
            scopeLabel = "Granted inbox",
        )
        val create = plan.operations[0] as PlannedOperation.CreateDirectory
        val move = plan.operations[1] as PlannedOperation.Move
        assertThat(create.name).isEqualTo("Uncertain")
        assertThat(move.destination).isInstanceOf(FileRef.Child::class.java)
        assertThat(plan.defaultSelectedSourceRefs).containsExactly(unresolved.artifact.stableRef)
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
